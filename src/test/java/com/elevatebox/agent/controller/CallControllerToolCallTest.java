package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.AgentToolService;
import com.elevatebox.agent.service.CallRecordService;
import com.elevatebox.agent.service.CallStateStore;
import com.elevatebox.agent.service.LeadClassifierService;
import com.elevatebox.agent.service.VapiService;
import com.elevatebox.agent.service.WhatsAppService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CallControllerToolCallTest {

    private WhatsAppService whatsApp;
    private LeadClassifierService classifier;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        whatsApp = mock(WhatsAppService.class);
        classifier = mock(LeadClassifierService.class);
        executor = mock(ThreadPoolTaskExecutor.class);
    }

    private CallController controller(boolean keywordMidCall) {
        CallStateStore state = new CallStateStore();
        return new CallController(mock(VapiService.class), whatsApp, classifier, state, executor,
                mock(CallRecordService.class), new AgentToolService(whatsApp, state, "https://example.com"),
                keywordMidCall);
    }

    @Test
    void toolCallIsAnsweredWithItsResult() {
        when(whatsApp.sendMidCallMessage(any())).thenReturn("wamid.1");

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
        when(whatsApp.sendMidCallMessage(any())).thenThrow(new RuntimeException("down"));

        var response = controller(false).vapiWebhook("""
                {"message":{"type":"tool-calls","call":{"id":"call-1"},
                 "toolCallList":[{"id":"tool-1","function":{"name":"send_whatsapp","arguments":{}}}]}}""");

        JsonObject result = JsonParser.parseString(response.getBody()).getAsJsonObject()
                .getAsJsonArray("results").get(0).getAsJsonObject();
        assertTrue(result.has("error"));
        assertFalse(result.has("result"));
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
}
