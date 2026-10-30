package com.elevatebox.agent.service;

import com.elevatebox.agent.controller.WhatsAppInbound;
import com.elevatebox.agent.persistence.Contact;
import com.elevatebox.agent.persistence.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.elevatebox.agent.service.OptInService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptInServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final String PHONE = "+919876543210";

    private ContactRepository contacts;
    private VapiService vapi;
    private WhatsAppService whatsApp;
    private OptInService optIn;

    @BeforeEach
    void setUp() {
        contacts = mock(ContactRepository.class);
        vapi = mock(VapiService.class);
        whatsApp = mock(WhatsAppService.class);
        when(contacts.findByPhone(any())).thenReturn(Optional.empty());
        when(contacts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        optIn = new OptInService(contacts, vapi, whatsApp, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24), 20);
    }

    private static WhatsAppInbound.Message msg(String id, String text) {
        return new WhatsAppInbound.Message(id, PHONE, "Rojan", text);
    }

    private Contact existing(Instant lastCallAt) {
        Contact c = new Contact();
        c.setPhone(PHONE);
        c.setLastCallAt(lastCallAt);
        c.setCallCount(1);
        when(contacts.findByPhone(PHONE)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void callRequestStartsACallAndRecordsConsent() {
        when(vapi.initiateCall(PHONE, "Rojan")).thenReturn("call-1");

        assertEquals(CALLED, optIn.handle(msg("m1", "Hi, please call me")));

        verify(vapi).initiateCall(PHONE, "Rojan");
        verify(contacts).save(argThat(c -> "call-1".equals(c.getLastCallId())
                && NOW.equals(c.getConsentAt()) && NOW.equals(c.getLastCallAt()) && c.getCallCount() == 1));
        verify(whatsApp).sendMessageTo(PHONE, OptInService.CALLING);
    }

    @Test
    void otherMessagesGetInstructionsAndNoCall() {
        assertEquals(ASKED_HOW, optIn.handle(msg("m1", "What do you do?")));
        assertEquals(ASKED_HOW, optIn.handle(msg("m2", null)));

        verifyNoInteractions(vapi);
        verify(whatsApp, times(2)).sendMessageTo(PHONE, OptInService.HOW_TO_ASK);
    }

    @Test
    void wordsContainingCallDoNotCount() {
        assertEquals(ASKED_HOW, optIn.handle(msg("m1", "I recall your ad")));
        verifyNoInteractions(vapi);
    }

    @Test
    void repeatedDeliveryIsIgnored() {
        when(vapi.initiateCall(any(), any())).thenReturn("call-1");

        optIn.handle(msg("m1", "call me"));
        assertEquals(DUPLICATE, optIn.handle(msg("m1", "call me")));

        verify(vapi, times(1)).initiateCall(any(), any());
    }

    @Test
    void numberCalledWithinTheCooldownIsNotCalledAgain() {
        existing(NOW.minus(Duration.ofHours(3)));

        assertEquals(COOLDOWN, optIn.handle(msg("m1", "CALL")));

        verifyNoInteractions(vapi);
        verify(whatsApp).sendMessageTo(PHONE, OptInService.COOLDOWN);
    }

    @Test
    void numberCanBeCalledAgainAfterTheCooldown() {
        Contact c = existing(NOW.minus(Duration.ofHours(25)));
        when(vapi.initiateCall(any(), any())).thenReturn("call-2");

        assertEquals(CALLED, optIn.handle(msg("m1", "call")));
        assertEquals(2, c.getCallCount());
    }

    @Test
    void dailyCapStopsCalls() {
        when(contacts.countByLastCallAtAfter(any())).thenReturn(20L);

        assertEquals(DAILY_LIMIT, optIn.handle(msg("m1", "call me")));

        verifyNoInteractions(vapi);
        verify(whatsApp).sendMessageTo(PHONE, OptInService.BUSY);
    }

    @Test
    void failedCallIsReportedAndCanBeRetried() {
        when(vapi.initiateCall(any(), any())).thenThrow(new RuntimeException("Vapi down")).thenReturn("call-1");

        assertEquals(CALL_FAILED, optIn.handle(msg("m1", "call me")));
        verify(whatsApp).sendMessageTo(PHONE, OptInService.FAILED);
        assertEquals(CALLED, optIn.handle(msg("m2", "call me")));
    }

    @Test
    void failedReplyDoesNotUndoTheCall() {
        when(vapi.initiateCall(any(), any())).thenReturn("call-1");
        when(whatsApp.sendMessageTo(any(), any())).thenThrow(new RuntimeException("Meta down"));

        assertEquals(CALLED, optIn.handle(msg("m1", "call me")));
    }

    @Test
    void phoneForCallFindsTheContact() {
        Contact c = new Contact();
        c.setPhone(PHONE);
        when(contacts.findFirstByLastCallId("call-1")).thenReturn(Optional.of(c));

        assertEquals(Optional.of(PHONE), optIn.phoneForCall("call-1"));
        assertEquals(Optional.empty(), optIn.phoneForCall(null));
        assertEquals(Optional.empty(), optIn.phoneForCall("unknown"));
    }

    @Test
    void phoneForCallNeverThrows() {
        when(contacts.findFirstByLastCallId(any())).thenThrow(new RuntimeException("db down"));
        assertEquals(Optional.empty(), optIn.phoneForCall("call-1"));
    }
}
