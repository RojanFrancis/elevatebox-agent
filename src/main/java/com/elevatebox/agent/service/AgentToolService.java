package com.elevatebox.agent.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Runs the tools the voice assistant can call during a live call.
 *
 * <p>Instead of this service guessing from keywords when to send a WhatsApp, the assistant
 * decides: it calls {@value #SEND_WHATSAPP} when the customer asks for details or shows clear
 * intent, Vapi forwards that to the webhook, and the answer returned here is read back to the
 * assistant, so it can tell the customer "I've just sent it to your WhatsApp".
 *
 * <p>The webhook waits for this answer while the customer is on the line, so nothing here calls
 * Gemini: the message is built from a fixed text per topic plus the assistant's own one-line
 * note. The mid-call WhatsApp is still sent at most once per call (see {@link CallStateStore}),
 * to the contact who asked for the call (see {@link OptInService}).
 */
@Slf4j
@Service
public class AgentToolService {

    public static final String SEND_WHATSAPP = "send_whatsapp";

    static final int MAX_NOTE_CHARS = 200;

    /** What the customer asked to receive. */
    enum Topic {
        PRICING("our e-commerce website packages and pricing"),
        PORTFOLIO("some websites we have built"),
        DETAILS("more about what we build");

        final String description;

        Topic(String description) {
            this.description = description;
        }

        static Topic from(String value) {
            if (value == null) {
                return DETAILS;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DETAILS;
            }
        }
    }

    /**
     * The answer for one tool call: exactly one of {@code result} and {@code error} is set.
     * Both are read by the assistant, so they are written as instructions to it.
     */
    public record Outcome(String result, String error) {
        static Outcome ok(String result) {
            return new Outcome(result, null);
        }

        static Outcome failed(String error) {
            return new Outcome(null, error);
        }
    }

    private final WhatsAppService whatsAppService;
    private final CallStateStore  callState;
    private final OptInService    optIn;
    private final String          brochureUrl;

    public AgentToolService(WhatsAppService whatsAppService,
                            CallStateStore callState,
                            OptInService optIn,
                            @Value("${agent.brochure-url:https://github.com/RojanFrancis/elevatebox-agent}")
                            String brochureUrl) {
        this.whatsAppService = whatsAppService;
        this.callState       = callState;
        this.optIn           = optIn;
        this.brochureUrl     = brochureUrl;
    }

    /**
     * Runs one tool call.
     *
     * @param callId    Vapi's call ID, or null if the payload had none
     * @param name      the tool name
     * @param arguments the tool arguments, never null
     * @return what to tell the assistant
     */
    public Outcome handle(String callId, String name, JsonObject arguments) {
        if (!SEND_WHATSAPP.equals(name)) {
            log.warn("Assistant called an unknown tool: {}", name);
            return Outcome.failed("This tool is not available. Carry on with the conversation.");
        }
        return sendWhatsApp(callId, Topic.from(text(arguments, "topic")), text(arguments, "note"));
    }

    private Outcome sendWhatsApp(String callId, Topic topic, String note) {
        if (callId != null && !callState.tryStartMidCall(callId)) {
            log.info("Mid-call WhatsApp already sent or on its way (callId={})", callId);
            return Outcome.ok("A WhatsApp message was already sent to the customer during this call. "
                    + "Tell them to check their WhatsApp; do not send another.");
        }

        boolean sent = false;
        try {
            // The contact who asked for this call, or the configured test number for browser calls
            String recipient = optIn.phoneForCall(callId).orElse(null);
            String id = whatsAppService.sendMidCallMessage(recipient, message(topic, note));
            sent = true;
            log.info("Mid-call WhatsApp sent by the assistant's tool call: topic={} id={}", topic, id);
            return Outcome.ok("Sent " + topic.description + " to the customer's WhatsApp just now. "
                    + "Tell them it is there and carry on with the conversation.");
        } catch (RuntimeException e) {
            log.error("Mid-call WhatsApp tool failed (callId={}): {}", callId, e.getMessage());
            return Outcome.failed("The WhatsApp message could not be sent right now. "
                    + "Tell the customer you will send the details right after the call.");
        } finally {
            if (callId != null) {
                callState.finishMidCall(callId, sent);
            }
        }
    }

    String message(Topic topic, String note) {
        StringBuilder text = new StringBuilder("Hi from ElevateBox! 👋");
        String cleanNote = clean(note);
        if (!cleanNote.isEmpty()) {
            text.append(' ').append(cleanNote);
        }
        text.append("\n\nAs promised on the call, here is ").append(topic.description).append(": ")
                .append(brochureUrl);
        return text.toString();
    }

    /** One line, no links or markup from the model, at most {@value #MAX_NOTE_CHARS} characters. */
    static String clean(String note) {
        if (note == null) {
            return "";
        }
        String oneLine = note.replaceAll("https?://\\S+", "").replaceAll("[*_~`]", "")
                .replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_NOTE_CHARS ? oneLine : oneLine.substring(0, MAX_NOTE_CHARS).trim() + "…";
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
