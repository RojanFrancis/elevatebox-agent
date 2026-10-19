package com.elevatebox.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CallStateStoreTest {

    private final CallStateStore store = new CallStateStore();

    @Test
    void buildsTranscriptWithVapiStyleLabels() {
        store.appendLine("c1", "assistant", "Hi, do you have two minutes?");
        store.appendLine("c1", "user", "Yes, go ahead");
        assertEquals("AI: Hi, do you have two minutes?\nUser: Yes, go ahead\n", store.transcript("c1"));
    }

    @Test
    void midCallRunsOnceThenNeverAgain() {
        assertTrue(store.tryStartMidCall("c1"));
        assertFalse(store.tryStartMidCall("c1"), "a second trigger while one is running must be ignored");
        store.finishMidCall("c1", true);
        assertFalse(store.tryStartMidCall("c1"), "after a message was sent there must be no second one");
    }

    @Test
    void midCallMayRetryAfterANonSend() {
        assertTrue(store.tryStartMidCall("c1"));
        store.finishMidCall("c1", false);
        assertTrue(store.tryStartMidCall("c1"));
    }

    @Test
    void callsDoNotShareState() {
        assertTrue(store.tryStartMidCall("c1"));
        assertTrue(store.tryStartMidCall("c2"));
    }

    @Test
    void endOfCallIsProcessedOnce() {
        assertTrue(store.markProcessed("c1"));
        assertFalse(store.markProcessed("c1"));
    }

    @Test
    void clearedCallCanBeProcessedAgain() {
        assertTrue(store.markProcessed("c1"));
        store.clearProcessed("c1");
        assertTrue(store.markProcessed("c1"));
    }

    @Test
    void memoryIsBounded() {
        for (int i = 0; i < CallStateStore.MAX_CALLS + 50; i++) {
            store.markProcessed("call-" + i);
        }
        // the oldest call has been evicted, so it looks new again; the newest is still remembered
        assertTrue(store.markProcessed("call-0"));
        assertFalse(store.markProcessed("call-" + (CallStateStore.MAX_CALLS + 49)));
    }

    @Test
    void transcriptSizeIsCapped() {
        String chunk = "x".repeat(5_000);
        for (int i = 0; i < 10; i++) {
            store.appendLine("c1", "user", chunk);
        }
        assertTrue(store.transcript("c1").length() <= CallStateStore.MAX_TRANSCRIPT_CHARS + 100);
    }
}
