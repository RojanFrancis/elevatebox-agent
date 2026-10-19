package com.elevatebox.agent.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory, per-call state for the webhook: the transcript so far, whether the mid-call
 * WhatsApp has been sent, and whether the end-of-call report was already processed.
 *
 * <p>This is what makes the webhook safe to retry. Vapi may deliver the same event twice, and
 * transcript events arrive many times per call, so "send once per call" and "process once per
 * call" need somewhere to live. Memory is bounded: only the most recent {@value #MAX_CALLS}
 * calls are kept. State is lost on restart, which is fine for a single-instance demo; a shared
 * store (Redis or a database table) would replace this class when running several instances.
 */
@Component
public class CallStateStore {

    static final int MAX_CALLS = 500;
    static final int MAX_TRANSCRIPT_CHARS = 20_000;

    private enum MidCall { IDLE, IN_FLIGHT, SENT }

    private static final class State {
        final StringBuilder transcript = new StringBuilder();
        MidCall midCall = MidCall.IDLE;
        boolean processed;
    }

    private final Map<String, State> calls = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
            return size() > MAX_CALLS;
        }
    };

    private State state(String callId) {
        State s = calls.get(callId);
        if (s == null) {
            s = new State();
            calls.put(callId, s);
        }
        return s;
    }

    /** Appends one finished utterance, labelled the same way Vapi labels its full transcripts. */
    public synchronized void appendLine(String callId, String role, String text) {
        State s = state(callId);
        if (s.transcript.length() + text.length() > MAX_TRANSCRIPT_CHARS) {
            return;
        }
        String speaker = "user".equals(role) ? "User" : "AI";
        s.transcript.append(speaker).append(": ").append(text).append('\n');
    }

    public synchronized String transcript(String callId) {
        return state(callId).transcript.toString();
    }

    /** Returns true if the caller may run the mid-call check now (nothing sent, nothing running). */
    public synchronized boolean tryStartMidCall(String callId) {
        State s = state(callId);
        if (s.midCall != MidCall.IDLE) {
            return false;
        }
        s.midCall = MidCall.IN_FLIGHT;
        return true;
    }

    /** Ends a mid-call check: sent means never again, otherwise a later trigger may retry. */
    public synchronized void finishMidCall(String callId, boolean sent) {
        state(callId).midCall = sent ? MidCall.SENT : MidCall.IDLE;
    }

    /** Returns true the first time a call ID is seen, false for every repeat. */
    public synchronized boolean markProcessed(String callId) {
        State s = state(callId);
        if (s.processed) {
            return false;
        }
        s.processed = true;
        s.transcript.setLength(0);
        return true;
    }

    /** Lets a retried end-of-call report through again, used when it could not be queued. */
    public synchronized void clearProcessed(String callId) {
        state(callId).processed = false;
    }
}
