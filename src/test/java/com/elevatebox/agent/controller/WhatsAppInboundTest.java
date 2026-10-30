package com.elevatebox.agent.controller;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppInboundTest {

    @Test
    void readsTextMessageWithProfileName() {
        List<WhatsAppInbound.Message> messages = WhatsAppInbound.parse("""
                {"object":"whatsapp_business_account","entry":[{"id":"waba","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp",
                  "contacts":[{"profile":{"name":"Rojan"},"wa_id":"919876543210"}],
                  "messages":[{"from":"919876543210","id":"wamid.A","timestamp":"1761800000",
                               "type":"text","text":{"body":"Hi, please call me"}}]}}]}]}""");

        assertEquals(1, messages.size());
        WhatsAppInbound.Message m = messages.get(0);
        assertEquals("wamid.A", m.id());
        assertEquals("+919876543210", m.phone());
        assertEquals("Rojan", m.name());
        assertEquals("Hi, please call me", m.text());
    }

    @Test
    void nonTextMessageHasNoText() {
        List<WhatsAppInbound.Message> messages = WhatsAppInbound.parse("""
                {"entry":[{"changes":[{"value":{"messages":[{"from":"919876543210","id":"wamid.B","type":"image","image":{}}]}}]}]}""");
        assertNull(messages.get(0).text());
        assertNull(messages.get(0).name());
    }

    @Test
    void deliveryStatusesAreNotMessages() {
        assertTrue(WhatsAppInbound.parse("""
                {"entry":[{"changes":[{"value":{"statuses":[{"id":"wamid.C","status":"delivered"}]}}]}]}""").isEmpty());
    }

    @Test
    void nonObjectBodyThrows() {
        assertThrows(RuntimeException.class, () -> WhatsAppInbound.parse("[]"));
    }
}
