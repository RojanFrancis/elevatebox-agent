package com.elevatebox.agent.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Turns the model's raw classification reply into a {@link GeminiService.ClassificationResult}.
 *
 * <p>The rule that matters: anything that is not a clear HOT, WARM or COLD becomes UNKNOWN.
 * The old parser defaulted to COLD, so an empty reply, a truncated reply or a 503 silently
 * turned a hot lead cold, and a "contains HOT" check read "not hot" as HOT. Never guessing is
 * what makes UNKNOWN safe to act on: the pipeline can skip or soften its next step instead.
 *
 * <p>Accepts the structured reply (a JSON object with {@code classification} and {@code reason},
 * possibly wrapped in a markdown fence) and, as a fallback, a bare single-word label.
 */
public final class ClassificationParser {

    private ClassificationParser() {}

    public static GeminiService.ClassificationResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return unknown("empty model reply");
        }
        String text = raw.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                String reason = string(object, "reason");
                return new GeminiService.ClassificationResult(toLabel(string(object, "classification")),
                        reason == null ? "" : reason);
            }
            if (parsed.isJsonPrimitive()) {
                return new GeminiService.ClassificationResult(toLabel(parsed.getAsString()), "");
            }
        } catch (RuntimeException notJson) {
            // fall through to the strict single-word check below
        }
        // Only an exact label is accepted, so "not hot" or "probably WARM, not HOT" is UNKNOWN
        return new GeminiService.ClassificationResult(toLabel(text), "");
    }

    private static GeminiService.ClassificationResult unknown(String reason) {
        return new GeminiService.ClassificationResult(GeminiService.LeadClassification.UNKNOWN, reason);
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static GeminiService.LeadClassification toLabel(String value) {
        if (value == null) {
            return GeminiService.LeadClassification.UNKNOWN;
        }
        String letters = value.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return switch (letters) {
            case "HOT"  -> GeminiService.LeadClassification.HOT;
            case "WARM" -> GeminiService.LeadClassification.WARM;
            case "COLD" -> GeminiService.LeadClassification.COLD;
            default     -> GeminiService.LeadClassification.UNKNOWN;
        };
    }
}
