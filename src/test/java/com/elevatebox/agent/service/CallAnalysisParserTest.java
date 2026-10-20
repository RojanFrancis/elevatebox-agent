package com.elevatebox.agent.service;

import org.junit.jupiter.api.Test;

import static com.elevatebox.agent.service.GeminiService.LeadClassification.*;
import static org.junit.jupiter.api.Assertions.*;

class CallAnalysisParserTest {

    private static final String FULL = """
            {"classification":"HOT","reason":"asked the price",
             "summary":"A salon owner wants a booking site.",
             "followUpRecap":"You run a salon and want online bookings, with about 40k budget.",
             "callbackRequested":true,"callbackWhen":"tomorrow morning"}""";

    @Test
    void parsesAFullReply() {
        CallAnalysis a = CallAnalysisParser.parse(FULL);
        assertEquals(HOT, a.label());
        assertEquals("asked the price", a.reason());
        assertEquals("A salon owner wants a booking site.", a.summary());
        assertEquals("You run a salon and want online bookings, with about 40k budget.", a.followUpRecap());
        assertTrue(a.callbackRequested());
        assertEquals("tomorrow morning", a.callbackWhen());
    }

    @Test
    void parsesAReplyWrappedInAMarkdownFence() {
        assertEquals(HOT, CallAnalysisParser.parse("```json\n" + FULL + "\n```").label());
    }

    @Test
    void callbackWithoutATimeIsNotACallbackRequest() {
        CallAnalysis a = CallAnalysisParser.parse(
                "{\"classification\":\"WARM\",\"callbackRequested\":true,\"callbackWhen\":\"  \"}");
        assertFalse(a.callbackRequested());
    }

    @Test
    void missingFieldsBecomeEmptyAndLabelUnknown() {
        CallAnalysis a = CallAnalysisParser.parse("{\"summary\":\"only a summary\"}");
        assertEquals(UNKNOWN, a.label());
        assertEquals("only a summary", a.summary());
        assertEquals("", a.followUpRecap());
        assertFalse(a.callbackRequested());
    }

    @Test
    void labelIsCaseInsensitiveAndUnrecognisedLabelIsUnknown() {
        assertEquals(WARM, CallAnalysisParser.parse("{\"classification\":\"warm\"}").label());
        assertEquals(UNKNOWN, CallAnalysisParser.parse("{\"classification\":\"lukewarm\"}").label());
    }

    @Test
    void unusableRepliesGiveTheUnavailableResult() {
        for (String bad : new String[] {null, "", "   ", "not json", "[1,2]", "{\"classification\":\"HO"}) {
            CallAnalysis a = CallAnalysisParser.parse(bad);
            assertEquals(UNKNOWN, a.label(), "input: " + bad);
            assertEquals("", a.followUpRecap(), "input: " + bad);
            assertFalse(a.callbackRequested(), "input: " + bad);
        }
    }
}
