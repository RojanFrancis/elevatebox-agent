package com.elevatebox.agent.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VapiEventTest {

    @Test
    void endOfCallReadsTranscriptFromArtifact() {
        VapiEvent e = VapiEvent.parse("""
                {"message":{"type":"end-of-call-report","call":{"id":"call-1"},
                 "artifact":{"transcript":"AI: Hi\\nUser: Yes I am interested"}}}""");
        assertTrue(e.isEndOfCall());
        assertEquals("call-1", e.callId());
        assertEquals("AI: Hi\nUser: Yes I am interested", e.endOfCallTranscript());
    }

    @Test
    void endOfCallStillReadsOlderLayouts() {
        assertEquals("old style", VapiEvent.parse(
                "{\"message\":{\"type\":\"end-of-call-report\",\"transcript\":\"old style\"}}").endOfCallTranscript());
        assertEquals("call style", VapiEvent.parse(
                "{\"message\":{\"type\":\"end-of-call-report\",\"call\":{\"transcript\":\"call style\"}}}").endOfCallTranscript());
        assertEquals("top level", VapiEvent.parse(
                "{\"type\":\"call.ended\",\"transcript\":\"top level\"}").endOfCallTranscript());
    }

    @Test
    void artifactTranscriptWinsOverOtherPlaces() {
        VapiEvent e = VapiEvent.parse(
                "{\"message\":{\"type\":\"end-of-call-report\",\"transcript\":\"legacy\",\"artifact\":{\"transcript\":\"current\"}}}");
        assertEquals("current", e.endOfCallTranscript());
    }

    @Test
    void finalUserTranscriptIsRecognised() {
        VapiEvent e = VapiEvent.parse("""
                {"message":{"type":"transcript","role":"user","transcriptType":"final",
                 "transcript":"please send me the pricing","call":{"id":"call-2"}}}""");
        assertTrue(e.isFinalTranscript());
        assertEquals("user", e.role());
        assertEquals("please send me the pricing", e.transcript());
        assertEquals("call-2", e.callId());
    }

    @Test
    void partialTranscriptIsNotFinal() {
        VapiEvent e = VapiEvent.parse(
                "{\"message\":{\"type\":\"transcript\",\"role\":\"user\",\"transcriptType\":\"partial\",\"transcript\":\"send m\"}}");
        assertTrue(e.isTranscript());
        assertFalse(e.isFinalTranscript());
    }

    @Test
    void finalOnlySubscriptionTypeIsFinal() {
        VapiEvent e = VapiEvent.parse(
                "{\"message\":{\"type\":\"transcript[transcriptType=\\\"final\\\"]\",\"role\":\"user\",\"transcript\":\"hello\"}}");
        assertTrue(e.isFinalTranscript());
    }

    @Test
    void otherEventsAreNeitherEndNorTranscript() {
        VapiEvent e = VapiEvent.parse("{\"message\":{\"type\":\"status-update\",\"status\":\"in-progress\"}}");
        assertFalse(e.isEndOfCall());
        assertFalse(e.isTranscript());
        assertNull(e.callId());
    }

    @Test
    void toolCallsAreParsedWithObjectArguments() {
        VapiEvent e = VapiEvent.parse("""
                {"message":{"type":"tool-calls","call":{"id":"call-3"},
                 "toolCallList":[{"id":"tool-1","type":"function",
                   "function":{"name":"send_whatsapp","arguments":{"topic":"pricing","note":"Hi"}}}]}}""");
        assertTrue(e.isToolCalls());
        assertEquals("call-3", e.callId());
        assertEquals(1, e.toolCalls().size());
        VapiEvent.ToolCall call = e.toolCalls().get(0);
        assertEquals("tool-1", call.id());
        assertEquals("send_whatsapp", call.name());
        assertEquals("pricing", call.arguments().get("topic").getAsString());
    }

    @Test
    void toolCallArgumentsMayBeAJsonString() {
        VapiEvent e = VapiEvent.parse("""
                {"message":{"type":"tool-calls","toolCallList":[{"id":"tool-1",
                 "function":{"name":"send_whatsapp","arguments":"{\\"topic\\":\\"portfolio\\"}"}}]}}""");
        assertEquals("portfolio", e.toolCalls().get(0).arguments().get("topic").getAsString());
    }

    @Test
    void otherEventsHaveNoToolCalls() {
        VapiEvent e = VapiEvent.parse("{\"message\":{\"type\":\"status-update\"}}");
        assertFalse(e.isToolCalls());
        assertTrue(e.toolCalls().isEmpty());
    }

    @Test
    void nonObjectBodyThrows() {
        assertThrows(RuntimeException.class, () -> VapiEvent.parse("[1,2,3]"));
        assertThrows(RuntimeException.class, () -> VapiEvent.parse("not json at all"));
    }
}
