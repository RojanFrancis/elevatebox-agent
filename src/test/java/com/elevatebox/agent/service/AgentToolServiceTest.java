package com.elevatebox.agent.service;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentToolServiceTest {

    private static final String BROCHURE = "https://example.com/brochure";

    private WhatsAppService whatsApp;
    private CallStateStore callState;
    private AgentToolService tools;

    @BeforeEach
    void setUp() {
        whatsApp = mock(WhatsAppService.class);
        callState = new CallStateStore();
        tools = new AgentToolService(whatsApp, callState, BROCHURE);
    }

    private static JsonObject args(String topic, String note) {
        JsonObject o = new JsonObject();
        if (topic != null) {
            o.addProperty("topic", topic);
        }
        if (note != null) {
            o.addProperty("note", note);
        }
        return o;
    }

    @Test
    void sendsThePricingMessageWithTheNoteAndLink() {
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.1");

        var outcome = tools.handle("call-1", "send_whatsapp", args("pricing", "Great to hear your saree shop is going online."));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendMidCallMessage(message.capture());
        assertTrue(message.getValue().contains("Great to hear your saree shop is going online."));
        assertTrue(message.getValue().contains("packages and pricing"));
        assertTrue(message.getValue().endsWith(BROCHURE));
        assertNull(outcome.error());
        assertTrue(outcome.result().startsWith("Sent our e-commerce website packages and pricing"));
    }

    @Test
    void sendsOnlyOncePerCall() {
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.1");

        tools.handle("call-1", "send_whatsapp", args("pricing", null));
        var second = tools.handle("call-1", "send_whatsapp", args("portfolio", null));

        verify(whatsApp, times(1)).sendMidCallMessage(any());
        assertTrue(second.result().contains("already sent"));
    }

    @Test
    void differentCallsEachGetAMessage() {
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.1");

        tools.handle("call-1", "send_whatsapp", args("pricing", null));
        tools.handle("call-2", "send_whatsapp", args("pricing", null));

        verify(whatsApp, times(2)).sendMidCallMessage(any());
    }

    @Test
    void aFailedSendIsReportedAndCanBeRetried() {
        when(whatsApp.sendMidCallMessage(any()))
                .thenThrow(new RuntimeException("Meta down"))
                .thenReturn("wamid.2");

        var failed = tools.handle("call-1", "send_whatsapp", args("pricing", null));
        var retried = tools.handle("call-1", "send_whatsapp", args("pricing", null));

        assertNull(failed.result());
        assertTrue(failed.error().contains("right after the call"));
        assertNull(retried.error());
        verify(whatsApp, times(2)).sendMidCallMessage(any());
    }

    @Test
    void unknownOrMissingTopicFallsBackToDetails() {
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.1");

        tools.handle("call-1", "send_whatsapp", args("discount codes", null));

        verify(whatsApp).sendMidCallMessage(contains("more about what we build"));
    }

    @Test
    void unknownToolIsAnErrorAndSendsNothing() {
        var outcome = tools.handle("call-1", "book_meeting", new JsonObject());

        assertNotNull(outcome.error());
        verifyNoInteractions(whatsApp);
    }

    @Test
    void noteIsOneLineWithoutLinksOrMarkupAndCapped() {
        assertEquals("Visit now", AgentToolService.clean("Visit *now*\n https://evil.example/x"));
        assertEquals("", AgentToolService.clean(null));
        String longNote = "a".repeat(500);
        assertEquals(AgentToolService.MAX_NOTE_CHARS + 1, AgentToolService.clean(longNote).length());
    }
}
