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
    }

    private static CallAnalysis analysis(GeminiService.LeadClassification label, String recap,
                                         boolean callbackRequested, String callbackWhen) {
        return new CallAnalysis(label, "because", "a summary", recap, callbackRequested, callbackWhen);
    }

    private void analysisIs(CallAnalysis analysis) {
        when(gemini.analyzeCall(any())).thenReturn(analysis);
    }

    // ---- post-call pipeline ----

    @Test
    void happyPathUsesOneGeminiCallAndHasNoFailures() {
        analysisIs(analysis(HOT, "recap text", false, ""));

        var result = service.processPostCall("transcript");

        assertEquals(HOT, result.classification());
        assertEquals("because", result.reason());
        assertEquals("a summary", result.summary());
        assertTrue(result.failedSteps().isEmpty());
        verify(gemini, times(1)).analyzeCall("transcript");
        verify(followUp).sendPostCallFollowUp("recap text", HOT);
        verify(callback, never()).sendCallbackConfirmation(any());
        verifyNoMoreInteractions(gemini);
    }

    @Test
    void callbackRequestFromAnInterestedLeadIsConfirmed() {
        analysisIs(analysis(WARM, "recap", true, "tomorrow at 10 AM"));

        var result = service.processPostCall("transcript");

        assertTrue(result.callbackRequested());
        assertEquals("tomorrow at 10 AM", result.callbackTime());
        verify(callback).sendCallbackConfirmation("tomorrow at 10 AM");
    }

    @Test
    void coldLeadsAreNotSentCallbackConfirmations() {
        analysisIs(analysis(COLD, "recap", true, "never"));

        var result = service.processPostCall("transcript");

        assertFalse(result.callbackRequested());
        verify(callback, never()).sendCallbackConfirmation(any());
    }

    @Test
    void aFailedWhatsAppSendDoesNotLoseTheRestOfTheResult() {
        analysisIs(analysis(HOT, "recap", true, "tomorrow"));
        doThrow(new RuntimeException("meta down")).when(followUp).sendPostCallFollowUp(any(), any());

        var result = service.processPostCall("transcript");

        assertEquals(List.of("follow-up"), result.failedSteps());
        assertEquals(HOT, result.classification());
        assertEquals("a summary", result.summary());
        assertTrue(result.callbackRequested());
        verify(callback).sendCallbackConfirmation("tomorrow");
    }

    @Test
    void aFailedCallbackConfirmationIsReportedNotThrown() {
        analysisIs(analysis(HOT, "recap", true, "tomorrow"));
        doThrow(new RuntimeException("meta down")).when(callback).sendCallbackConfirmation(any());

        var result = service.processPostCall("transcript");

        assertEquals(List.of("callback-confirmation"), result.failedSteps());
        verify(followUp).sendPostCallFollowUp("recap", HOT);
    }

    @Test
    void geminiOutageGivesUnknownAndStillSendsAGenericFollowUp() {
        when(gemini.analyzeCall(any())).thenThrow(new RuntimeException("429"));

        var result = service.processPostCall("transcript");

        assertEquals(UNKNOWN, result.classification());
        assertEquals(List.of("analysis"), result.failedSteps());
        verify(followUp).sendPostCallFollowUp("", UNKNOWN);
        verify(callback, never()).sendCallbackConfirmation(any());
    }

    // ---- mid-call pipeline ----

    private void classifiedAs(GeminiService.LeadClassification label) {
        when(gemini.classifyLead(any())).thenReturn(new GeminiService.ClassificationResult(label, "because"));
    }

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
