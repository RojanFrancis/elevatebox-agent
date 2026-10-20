package com.elevatebox.agent.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Turns Gemini's raw reply to the call-analysis request into a {@link CallAnalysis}.
 *
 * <p>Never throws and never guesses: an empty, truncated or malformed reply gives
 * {@link CallAnalysis#unavailable()}, and an unrecognised label gives UNKNOWN (see
 * {@link ClassificationParser} for why that matters). Missing text fields become empty strings.
 */
public final class CallAnalysisParser {

    private CallAnalysisParser() {}

    public static CallAnalysis parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CallAnalysis.unavailable();
        }
        String text = raw.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return CallAnalysis.unavailable();
            }
            JsonObject object = parsed.getAsJsonObject();
            return new CallAnalysis(
                    ClassificationParser.toLabel(string(object, "classification")),
                    string(object, "reason"),
                    string(object, "summary"),
                    string(object, "followUpRecap"),
                    bool(object, "callbackRequested"),
                    string(object, "callbackWhen"));
        } catch (RuntimeException e) {
            return CallAnalysis.unavailable();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }
}
