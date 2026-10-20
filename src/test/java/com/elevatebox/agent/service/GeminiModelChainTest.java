package com.elevatebox.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeminiModelChainTest {

    @Test
    void primaryAloneWhenThereAreNoFallbacks() {
        assertEquals(List.of("model-a"), GeminiService.modelChain("model-a", ""));
        assertEquals(List.of("model-a"), GeminiService.modelChain("model-a", null));
        assertEquals(List.of("model-a"), GeminiService.modelChain("model-a", " , ,"));
    }

    @Test
    void fallbacksFollowThePrimaryInOrder() {
        assertEquals(List.of("model-a", "model-b", "model-c"),
                GeminiService.modelChain("model-a", "model-b,model-c"));
    }

    @Test
    void namesAreTrimmedAndDuplicatesDropped() {
        assertEquals(List.of("model-a", "model-b"),
                GeminiService.modelChain(" model-a ", " model-b , model-a ,model-b"));
    }
}
