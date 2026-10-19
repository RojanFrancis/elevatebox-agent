package com.elevatebox.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static com.elevatebox.agent.service.GeminiService.LeadClassification.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LeadClassifierServiceTest {

    private static final String BROCHURE = "https://example.com/brochure";

    private GeminiService gemini;
    private WhatsAppService whatsApp;
    private FollowUpService followUp;
    private CallbackService callback;
    private LeadClassifierService service;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiService.class);
        whatsApp = mock(WhatsAppService.class);
        followUp = mock(FollowUpService.class);
        callback = mock(CallbackService.class);
        service = new LeadClassifierService(gemini, whatsApp, followUp, callback, BROCHURE);
        when(gemini.summarizeConversation(any())).thenReturn("a summary");
    }

    private void classifiedAs(GeminiService.LeadClassification label) {
        when(gemini.classifyLead(any())).thenReturn(new GeminiService.ClassificationResult(label, "because"));
    }

    // ---- post-call pipeline ----

    @Test
    void happyPathHasNoFailures() {
        classifiedAs(HOT);
        when(callback.scheduleCallback(any())).thenReturn(new CallbackService.CallbackResult(false, null, null));

        var result = service.processPostCall("transcript");

        assertEquals(HOT, result.classification());
        assertEquals("because", result.reason());
        assertEquals("a summary", result.summary());
        assertTrue(result.failedSteps().isEmpty());
        verify(followUp).sendPostCallFollowUp("transcript", HOT);
    }

    @Test
    void aFailedWhatsAppSendDoesNotStopTheRestOfThePipeline() {
        classifiedAs(HOT);
        doThrow(new RuntimeException("meta down")).when(followUp).sendPostCallFollowUp(any(), any());
        when(callback.scheduleCallback(any()))
                .thenReturn(new CallbackService.CallbackResult(true, "2026-10-20 10:00", "tomorrow at 10 AM"));

        var result = service.processPostCall("transcript");

        assertEquals(List.of("follow-up"), result.failedSteps());
        assertTrue(result.callbackRequested());
        assertEquals("tomorrow at 10 AM", result.callbackTime());
        assertEquals("a summary", result.summary());
        verify(callback).sendCallbackConfirmation("tomorrow at 10 AM");
    }

    @Test
    void classificationOutageGivesUnknownNotCold() {
        when(gemini.classifyLead(any())).thenThrow(new RuntimeException("503"));

        var result = service.processPostCall("transcript");

        assertEquals(UNKNOWN, result.classification());
        assertEquals(List.of("classification"), result.failedSteps());
        verify(followUp).sendPostCallFollowUp("transcript", UNKNOWN);
        verify(callback, never()).scheduleCallback(any());
    }

    @Test
    void coldLeadsAreNotAskedAboutCallbacks() {
        classifiedAs(COLD);

        service.processPostCall("transcript");

        verify(callback, never()).scheduleCallback(any());
        verify(callback, never()).sendCallbackConfirmation(any());
    }

    @Test
    void aFailedSummaryStillReturnsTheRest() {
        classifiedAs(WARM);
        when(callback.scheduleCallback(any())).thenReturn(new CallbackService.CallbackResult(false, null, null));
        when(gemini.summarizeConversation(any())).thenThrow(new RuntimeException("503"));

        var result = service.processPostCall("transcript");

        assertEquals(WARM, result.classification());
        assertEquals("", result.summary());
        assertEquals(List.of("summary"), result.failedSteps());
    }

    // ---- mid-call pipeline ----

    @Test
    void midCallReplacesTheLinkPlaceholder() {
        classifiedAs(HOT);
        when(gemini.generateMidCallMessage(any())).thenReturn("Here is our work: [LINK] Talk soon!");
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.1");

        Optional<String> sid = service.processMidCall("User: send me the pricing");

        assertEquals(Optional.of("wamid.1"), sid);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendMidCallMessage(sent.capture());
        assertEquals("Here is our work: " + BROCHURE + " Talk soon!", sent.getValue());
        assertFalse(sent.getValue().contains("[LINK]"));
    }

    @Test
    void midCallAppendsTheLinkIfTheModelForgotIt() {
        classifiedAs(HOT);
        when(gemini.generateMidCallMessage(any())).thenReturn("Great talking to you!");
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.2");

        service.processMidCall("transcript");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendMidCallMessage(sent.capture());
        assertTrue(sent.getValue().contains(BROCHURE));
    }

    @Test
    void midCallUsesAFallbackMessageWhenTheModelReturnsNothing() {
        classifiedAs(HOT);
        when(gemini.generateMidCallMessage(any())).thenReturn("");
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.3");

        service.processMidCall("transcript");

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendMidCallMessage(sent.capture());
        assertTrue(sent.getValue().contains(BROCHURE));
    }

    @Test
    void midCallSendsNothingUnlessHot() {
        for (var label : List.of(WARM, COLD, UNKNOWN)) {
            classifiedAs(label);
            assertEquals(Optional.empty(), service.processMidCall("transcript"));
        }
        verify(whatsApp, never()).sendMidCallMessage(any());
        verify(gemini, never()).generateMidCallMessage(any());
    }
}
