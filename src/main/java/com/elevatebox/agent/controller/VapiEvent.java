package com.elevatebox.agent.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The parts of a Vapi server message this service cares about.
 *
 * <p>Vapi posts {@code {"message": {"type": ..., ...}}} to the Server URL. Two layouts matter:
 * <ul>
 *   <li>{@code end-of-call-report}: the call ID is at {@code message.call.id} and the full
 *       transcript at {@code message.artifact.transcript}.</li>
 *   <li>{@code transcript} (or {@code transcript[transcriptType="final"]}): one utterance, with
 *       {@code role} ("user" or "assistant"), {@code transcriptType} ("partial" or "final") and
 *       the text in {@code transcript}.</li>
 * </ul>
 * Older layouts (top-level {@code type}, {@code message.transcript}) are still understood.
 *
 * @param type                 the message type, never null
 * @param callId               Vapi's call ID, or null if the payload had none
 * @param role                 speaker of a transcript event, or null
 * @param transcriptType       "partial" or "final" for transcript events, or null
 * @param transcript           the utterance text of a transcript event, or null
 * @param endOfCallTranscript  the full call transcript of an end-of-call report, or null
 */
public record VapiEvent(String type, String callId, String role, String transcriptType,
                        String transcript, String endOfCallTranscript) {

    /** Parses a webhook body. Throws a RuntimeException if it is not a JSON object. */
    public static VapiEvent parse(String payload) {
        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        JsonObject message = object(root, "message");
        JsonObject body = message != null ? message : root;

        String type = text(body, "type");
        if (type == null) {
            type = text(root, "type");
        }

        JsonObject call = object(body, "call");
        if (call == null) {
            call = object(root, "call");
        }
        String callId = call != null ? text(call, "id") : null;

        JsonObject artifact = object(body, "artifact");
        String endTranscript = firstNonBlank(
                artifact != null ? text(artifact, "transcript") : null,
                text(body, "transcript"),
                call != null ? text(call, "transcript") : null);

        return new VapiEvent(type == null ? "" : type, callId, text(body, "role"),
                text(body, "transcriptType"), text(body, "transcript"), endTranscript);
    }

    public boolean isEndOfCall() {
        return "end-of-call-report".equals(type) || "call.ended".equals(type);
    }

    public boolean isTranscript() {
        return type.startsWith("transcript");
    }

    /** True only for finished utterances, never for the many partial updates. */
    public boolean isFinalTranscript() {
        return isTranscript() && ("final".equals(transcriptType) || type.contains("final"));
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
