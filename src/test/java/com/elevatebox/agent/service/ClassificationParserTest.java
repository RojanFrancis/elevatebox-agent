package com.elevatebox.agent.service;

import org.junit.jupiter.api.Test;

import static com.elevatebox.agent.service.GeminiService.LeadClassification.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassificationParserTest {

    @Test
    void parsesStructuredReply() {
        var result = ClassificationParser.parse("{\"classification\":\"HOT\",\"reason\":\"asked the price and wants to start this week\"}");
        assertEquals(HOT, result.label());
        assertEquals("asked the price and wants to start this week", result.reason());
    }

    @Test
    void parsesReplyWrappedInMarkdownFence() {
        var result = ClassificationParser.parse("```json\n{\"classification\":\"WARM\",\"reason\":\"needs partner approval\"}\n```");
        assertEquals(WARM, result.label());
    }

    @Test
    void labelIsCaseInsensitive() {
        assertEquals(WARM, ClassificationParser.parse("{\"classification\":\"warm\",\"reason\":\"x\"}").label());
    }

    @Test
    void acceptsBareSingleWordLabel() {
        assertEquals(COLD, ClassificationParser.parse("COLD").label());
        assertEquals(HOT, ClassificationParser.parse("  HOT\n").label());
    }

    @Test
    void notHotIsNeverReadAsHot() {
        assertEquals(UNKNOWN, ClassificationParser.parse("not hot").label());
        assertEquals(UNKNOWN, ClassificationParser.parse("The lead is not HOT, probably WARM").label());
    }

    @Test
    void emptyOrMissingReplyIsUnknownNotCold() {
        assertEquals(UNKNOWN, ClassificationParser.parse("").label());
        assertEquals(UNKNOWN, ClassificationParser.parse("   ").label());
        assertEquals(UNKNOWN, ClassificationParser.parse(null).label());
    }

    @Test
    void unrecognisedOrMissingLabelIsUnknown() {
        assertEquals(UNKNOWN, ClassificationParser.parse("{\"classification\":\"lukewarm\",\"reason\":\"x\"}").label());
        assertEquals(UNKNOWN, ClassificationParser.parse("{\"reason\":\"no label here\"}").label());
        assertEquals(UNKNOWN, ClassificationParser.parse("{\"classification\":\"UNKNOWN\",\"reason\":\"too short\"}").label());
    }

    @Test
    void truncatedJsonIsUnknown() {
        assertEquals(UNKNOWN, ClassificationParser.parse("{\"classification\":\"HO").label());
    }
}
