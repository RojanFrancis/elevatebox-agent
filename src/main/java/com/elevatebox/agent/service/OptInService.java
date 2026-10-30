package com.elevatebox.agent.service;

import com.elevatebox.agent.controller.WhatsAppInbound;
import com.elevatebox.agent.persistence.Contact;
import com.elevatebox.agent.persistence.ContactRepository;
import com.elevatebox.agent.util.Logs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Consent-first calling: the agent only calls people who messaged the business on WhatsApp and
 * asked for a call.
 *
 * <p>Why the customer messages first:
 * <ul>
 *   <li><b>Consent.</b> Nobody is cold-called; every call answers a request.</li>
 *   <li><b>WhatsApp's 24-hour rule.</b> Free-form messages are only delivered within 24 hours of
 *       the customer's last message, so their "call me" is what lets the mid-call WhatsApp
 *       arrive.</li>
 *   <li><b>Cost.</b> Each call costs money, so a number is called at most once per cooldown
 *       ({@code optin.cooldown-hours}) and there is a daily cap across everyone
 *       ({@code optin.calls-per-day}).</li>
 * </ul>
 *
 * <p>A message that mentions "call" is a request; anything else gets a short reply explaining
 * how to ask. Every outcome is answered on WhatsApp, so the customer always knows what happens.
 * Methods are synchronized so the limits hold when Meta delivers messages concurrently; this is a
 * single-instance service.
 */
@Slf4j
@Service
public class OptInService {

    static final Pattern CALL_REQUEST = Pattern.compile("\\bcall\\b", Pattern.CASE_INSENSITIVE);
    static final int MAX_SEEN_IDS = 1_000;

    static final String HOW_TO_ASK = "Hi from ElevateBox! 👋 Our AI assistant Ria can call you for a quick chat "
            + "about building your business website. Reply *CALL* and she'll ring you in a minute.";
    static final String CALLING = "Great, Ria from ElevateBox is calling you now 📞 "
            + "She can send you pricing and examples right here during the call.";
    static final String COOLDOWN = "Ria already called you recently, so we won't call again just yet. "
            + "Reply here with any question and the team will get back to you.";
    static final String BUSY = "Ria has reached today's call limit. Please reply *CALL* again tomorrow, "
            + "or leave your question here and the team will get back to you.";
    static final String FAILED = "Sorry, we couldn't place the call just now. Please reply *CALL* to try again in a few minutes.";

    private final ContactRepository contacts;
    private final VapiService vapiService;
    private final WhatsAppService whatsAppService;
    private final Clock clock;
    private final Duration cooldown;
    private final int callsPerDay;

    /** Recently handled message IDs, so Meta's repeated deliveries are ignored. */
    private final Map<String, Boolean> seen = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_SEEN_IDS;
        }
    };

    @Autowired
    public OptInService(ContactRepository contacts, VapiService vapiService, WhatsAppService whatsAppService,
                        @Value("${optin.cooldown-hours:24}") long cooldownHours,
                        @Value("${optin.calls-per-day:20}") int callsPerDay) {
        this(contacts, vapiService, whatsAppService, Clock.systemUTC(), Duration.ofHours(cooldownHours), callsPerDay);
    }

    OptInService(ContactRepository contacts, VapiService vapiService, WhatsAppService whatsAppService,
                 Clock clock, Duration cooldown, int callsPerDay) {
        this.contacts = contacts;
        this.vapiService = vapiService;
        this.whatsAppService = whatsAppService;
        this.clock = clock;
        this.cooldown = cooldown;
        this.callsPerDay = callsPerDay;
    }

    /** What happened to one message; returned for tests and logs. */
    public enum Outcome { DUPLICATE, ASKED_HOW, CALLED, COOLDOWN, DAILY_LIMIT, CALL_FAILED }

    /** Handles one incoming WhatsApp message. Never throws. */
    public synchronized Outcome handle(WhatsAppInbound.Message message) {
        if (seen.put(message.id(), Boolean.TRUE) != null) {
            return Outcome.DUPLICATE;
        }
        try {
            return decide(message);
        } catch (RuntimeException e) {
            log.error("Opt-in handling failed for {}: {}", Logs.maskPhone(message.phone()), e.getMessage(), e);
            return Outcome.CALL_FAILED;
        }
    }

    /**
     * The phone number of the contact a Vapi call was placed to, if the call came from an opt-in.
     * Browser test calls and calls to the configured test number have none. Never throws.
     */
    public Optional<String> phoneForCall(String callId) {
        if (callId == null) {
            return Optional.empty();
        }
        try {
            return contacts.findFirstByLastCallId(callId).map(Contact::getPhone);
        } catch (RuntimeException e) {
            log.warn("Could not look up the contact for call {}: {}", callId, e.getMessage());
            return Optional.empty();
        }
    }

    private Outcome decide(WhatsAppInbound.Message message) {
        Instant now = clock.instant();
        Contact contact = contacts.findByPhone(message.phone()).orElseGet(() -> {
            Contact c = new Contact();
            c.setPhone(message.phone());
            return c;
        });
        if (message.name() != null && !message.name().isBlank()) {
            contact.setName(message.name().length() > 100 ? message.name().substring(0, 100) : message.name());
        }
        contact.setLastMessageAt(now);

        if (message.text() == null || !CALL_REQUEST.matcher(message.text()).find()) {
            contacts.save(contact);
            reply(contact, HOW_TO_ASK);
            return Outcome.ASKED_HOW;
        }

        contact.setConsentAt(now);
        if (contact.getLastCallAt() != null && contact.getLastCallAt().isAfter(now.minus(cooldown))) {
            contacts.save(contact);
            reply(contact, COOLDOWN);
            return Outcome.COOLDOWN;
        }
        Instant startOfDay = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (contacts.countByLastCallAtAfter(startOfDay) >= callsPerDay) {
            contacts.save(contact);
            reply(contact, BUSY);
            return Outcome.DAILY_LIMIT;
        }

        String callId;
        try {
            callId = vapiService.initiateCall(contact.getPhone(), contact.getName());
        } catch (RuntimeException e) {
            log.error("Could not start the opt-in call to {}: {}", Logs.maskPhone(contact.getPhone()), e.getMessage());
            contacts.save(contact);
            reply(contact, FAILED);
            return Outcome.CALL_FAILED;
        }

        contact.setLastCallAt(now);
        contact.setLastCallId(callId);
        contact.setCallCount(contact.getCallCount() + 1);
        contacts.save(contact);
        reply(contact, CALLING);
        log.info("Opt-in call started to {} (callId={})", Logs.maskPhone(contact.getPhone()), callId);
        return Outcome.CALLED;
    }

    /** A failed reply must not undo a call that already started, so it is only logged. */
    private void reply(Contact contact, String text) {
        try {
            whatsAppService.sendMessageTo(contact.getPhone(), text);
        } catch (RuntimeException e) {
            log.warn("Could not reply on WhatsApp to {}: {}", Logs.maskPhone(contact.getPhone()), e.getMessage());
        }
    }
}
