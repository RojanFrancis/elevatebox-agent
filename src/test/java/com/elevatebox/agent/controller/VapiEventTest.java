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
    void nonObjectBodyThrows() {
        assertThrows(RuntimeException.class, () -> VapiEvent.parse("[1,2,3]"));
        assertThrows(RuntimeException.class, () -> VapiEvent.parse("not json at all"));
    }
}
