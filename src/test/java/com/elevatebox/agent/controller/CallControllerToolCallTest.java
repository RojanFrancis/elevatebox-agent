package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.AgentToolService;
import com.elevatebox.agent.service.CallRecordService;
import com.elevatebox.agent.service.CallStateStore;
import com.elevatebox.agent.service.LeadClassifierService;
import com.elevatebox.agent.service.OptInService;
import com.elevatebox.agent.service.OutboundCaller;
import com.elevatebox.agent.service.WhatsAppService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CallControllerToolCallTest {

    private WhatsAppService whatsApp;
    private LeadClassifierService classifier;
    private ThreadPoolTaskExecutor executor;
    private OptInService optIn;
    private CallRecordService records;

    @BeforeEach
    void setUp() {
        whatsApp = mock(WhatsAppService.class);
        classifier = mock(LeadClassifierService.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        optIn = mock(OptInService.class);
        records = mock(CallRecordService.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));
    }

    private CallController controller(boolean keywordMidCall) {
        CallStateStore state = new CallStateStore();
        return new CallController(mock(OutboundCaller.class), "+910000000000", whatsApp, classifier, state, executor,
                records, new AgentToolService(whatsApp, state, optIn, "https://example.com"),
                optIn, keywordMidCall);
    }

    @Test
    void toolCallIsAnsweredWithItsResult() {
        when(whatsApp.sendMidCallMessage(any(), any())).thenReturn("wamid.1");

        var response = controller(false).vapiWebhook("""
                {"message":{"type":"tool-calls","call":{"id":"call-1"},
                 "toolCallList":[{"id":"tool-1","type":"function",
                   "function":{"name":"send_whatsapp","arguments":{"topic":"pricing"}}}]}}""");

        assertEquals(200, response.getStatusCode().value());
        JsonObject result = JsonParser.parseString(response.getBody()).getAsJsonObject()
                .getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals("tool-1", result.get("toolCallId").getAsString());
        assertTrue(result.get("result").getAsString().startsWith("Sent"));
        assertFalse(result.has("error"));
    }

    @Test
    void failedToolCallIsAnsweredWithAnError() {
        when(whatsApp.sendMidCallMessage(any(), any())).thenThrow(new RuntimeException("down"));

        var response = controller(false).vapiWebhook("""
                {"message":{"type":"tool-calls","call":{"id":"call-1"},
                 "toolCallList":[{"id":"tool-1","function":{"name":"send_whatsapp","arguments":{}}}]}}""");

        JsonObject result = JsonParser.parseString(response.getBody()).getAsJsonObject()
                .getAsJsonArray("results").get(0).getAsJsonObject();
        assertTrue(result.has("error"));
        assertFalse(result.has("result"));
    }

    @Test
    void apiRequestToolSendsOncePerCallId() {
        when(whatsApp.sendMidCallMessage(any(), any())).thenReturn("wamid.1");
        CallController controller = controller(false);

        var first = controller.sendWhatsAppTool("call-1", "{\"topic\":\"pricing\",\"note\":\"Hi\"}");
        var second = controller.sendWhatsAppTool("call-1", "{\"topic\":\"pricing\"}");

        assertEquals(200, first.getStatusCode().value());
        assertEquals(true, first.getBody().get("success"));
        assertTrue(((String) second.getBody().get("result")).contains("already sent"));
        verify(whatsApp, times(1)).sendMidCallMessage(any(), any());
    }

    @Test
    void apiRequestToolFailureIs502() {
        when(whatsApp.sendMidCallMessage(any(), any())).thenThrow(new RuntimeException("down"));

        var response = controller(false).sendWhatsAppTool("call-1", "{}");

        assertEquals(502, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void apiRequestToolRejectsANonObjectBody() {
        var response = controller(false).sendWhatsAppTool("call-1", "[1]");

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(whatsApp);
    }

    @Test
    void keywordsDoNotTriggerTheMidCallCheckByDefault() {
        controller(false).vapiWebhook("""
                {"message":{"type":"transcript","role":"user","transcriptType":"final",
                 "transcript":"please send me the pricing","call":{"id":"call-1"}}}""");

        verifyNoInteractions(executor);
    }

    @Test
    void keywordsTriggerTheMidCallCheckWhenEnabled() {
        controller(true).vapiWebhook("""
                {"message":{"type":"transcript","role":"user","transcriptType":"final",
                 "transcript":"please send me the pricing","call":{"id":"call-1"}}}""");

        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void bolnaToolFindsTheCallByTheCustomersNumber() {
        when(optIn.recentCallIdForPhone("+919876543210")).thenReturn(java.util.Optional.of("exec-1"));
        when(optIn.phoneForCall("exec-1")).thenReturn(java.util.Optional.of("+919876543210"));
        when(whatsApp.sendMidCallMessage(any(), any())).thenReturn("wamid.1");
        CallController controller = controller(false);

        controller.sendWhatsAppTool(null, "{\"topic\":\"pricing\",\"to_number\":\"+919876543210\"}");
        var second = controller.sendWhatsAppTool(null, "{\"topic\":\"pricing\",\"to_number\":\"+919876543210\"}");

        verify(whatsApp, times(1)).sendMidCallMessage(eq("+919876543210"), any());
        assertTrue(((String) second.getBody().get("result")).contains("already sent"));
    }

    @Test
    void bolnaCompletedCallRunsThePostCallPipelineOnce() {
        CallController controller = controller(false);
        String body = """
                {"id":"exec-1","status":"completed","transcript":"assistant: Hi\nuser: I want a store"}""";

        assertEquals("OK", controller.bolnaCallback(body).getBody());
        assertEquals("DUPLICATE", controller.bolnaCallback(body).getBody());

        verify(classifier, times(1)).processPostCall(contains("I want a store"), any());
        verify(records).save(eq("exec-1"), eq("bolna"), any());
    }

    @Test
    void bolnaInProgressStatusesAreIgnored() {
        controller(false).bolnaCallback("{\"id\":\"exec-1\",\"status\":\"in-progress\"}");

        verifyNoInteractions(classifier, optIn);
    }

    @Test
    void bolnaUnansweredCallIsHandedToTheOptInOnce() {
        CallController controller = controller(false);

        controller.bolnaCallback("{\"id\":\"exec-1\",\"status\":\"no-answer\"}");
        controller.bolnaCallback("{\"id\":\"exec-1\",\"status\":\"no-answer\"}");

        verify(optIn, times(1)).callNotConnected("exec-1");
        verifyNoInteractions(classifier);
    }

    @Test
    void bolnaCallbackRejectsANonObjectBody() {
        assertEquals(400, controller(false).bolnaCallback("[1]").getStatusCode().value());
    }
}
