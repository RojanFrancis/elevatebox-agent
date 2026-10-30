package com.elevatebox.agent.service;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VapiToolDefinitionTest {

    @Test
    void sendWhatsAppToolMatchesTheHandler() {
        JsonObject tool = VapiService.sendWhatsAppTool();
        JsonObject function = tool.getAsJsonObject("function");

        assertEquals("function", tool.get("type").getAsString());
        assertEquals(AgentToolService.SEND_WHATSAPP, function.get("name").getAsString());
        JsonObject topic = function.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("topic");
        for (var value : topic.getAsJsonArray("enum")) {
            assertEquals(value.getAsString().toUpperCase(),
                    AgentToolService.Topic.from(value.getAsString()).name());
        }
    }
}
