package com.elevatebox.agent.service;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;

class BolnaServiceTest {

    private BolnaService service(String apiKey, String agentId, String from) {
        return new BolnaService(WebClient.builder(), apiKey, agentId, from);
    }

    @Test
    void payloadCarriesTheAgentNumberAndName() {
        JsonObject body = service("key", "agent-1", "").payload("+919876543210", "Rojan");

        assertEquals("agent-1", body.get("agent_id").getAsString());
        assertEquals("+919876543210", body.get("recipient_phone_number").getAsString());
        assertEquals("Rojan", body.getAsJsonObject("user_data").get("customer_name").getAsString());
        assertFalse(body.has("from_phone_number"));
    }

    @Test
    void fromNumberIsSentOnlyWhenSet() {
        JsonObject body = service("key", "agent-1", "+918035739222").payload("+919876543210", null);

        assertEquals("+918035739222", body.get("from_phone_number").getAsString());
        assertEquals("there", body.getAsJsonObject("user_data").get("customer_name").getAsString());
    }

    @Test
    void executionIdIsReadFromTheResponse() {
        assertEquals("exec-1", BolnaService.executionId("{\"message\":\"done\",\"status\":\"queued\",\"execution_id\":\"exec-1\"}"));
        assertEquals("unknown", BolnaService.executionId("not json"));
    }

    @Test
    void refusesToCallWhenNotConfigured() {
        assertThrows(IllegalStateException.class, () -> service("", "agent-1", "").initiateCall("+919876543210", null));
    }
}
