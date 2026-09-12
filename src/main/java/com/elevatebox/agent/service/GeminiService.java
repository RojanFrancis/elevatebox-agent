package com.elevatebox.agent.service;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class GeminiService {

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String MODEL = "gemini-3.6-flash";

    @Value("${gemini.api-key}")
    private String geminiApiKey;

    private final WebClient webClient;
    private final Gson gson = new Gson();

    public GeminiService(WebClient.Builder builder) {
        this.webClient = builder.baseUrl(GEMINI_BASE_URL).build();
    }

    // ─── Lead Classification ─────────────────────────────────────────────────

    /**
     * Classify a lead based on the full call transcript.
     * Returns LeadClassification enum: HOT / WARM / COLD
     */
    public LeadClassification classifyLead(String transcript) {
        String prompt = buildClassificationPrompt(transcript);
        String raw    = callGemini(prompt);
        return parseClassification(raw);
    }

    /**
     * Generate a post-call summary of the conversation.
     */
    public String summarizeConversation(String transcript) {
        String prompt = "Summarize the following sales call transcript in 3-4 sentences. " +
                "Highlight: what the prospect's main interest was, any objections raised, and agreed next steps.\n\n" +
                "Transcript:\n" + transcript;
        return callGemini(prompt);
    }

    /**
     * Generate a personalised WhatsApp follow-up message based on the conversation.
     */
    public String generateFollowUpMessage(String transcript, LeadClassification classification) {
        String prompt = String.format(
                "You are writing a WhatsApp follow-up message after a sales call for ElevateBox.\n" +
                "The lead was classified as: %s\n\n" +
                "Call transcript:\n%s\n\n" +
                "Write a short, friendly WhatsApp message (max 3 sentences) that:\n" +
                "- Thanks them for their time\n" +
                "- References something specific from the conversation\n" +
                "- Includes a clear next step appropriate for a %s lead\n" +
                "Do not use formal email language. Keep it conversational.",
                classification, transcript, classification);

        return callGemini(prompt);
    }

    /**
     * Generate a mid-call WhatsApp message (e.g., sending a brochure link while on call).
     */
    public String generateMidCallMessage(String partialTranscript) {
        String prompt = "Based on this partial sales call transcript, write a very short WhatsApp message " +
                "(1-2 sentences) to send to the prospect RIGHT NOW while on the call. " +
                "It should reinforce what's being discussed and include a helpful link placeholder [LINK].\n\n" +
                "Transcript so far:\n" + partialTranscript;
        return callGemini(prompt);
    }

    // ─── Core Gemini API call ─────────────────────────────────────────────────

    public String callGemini(String prompt) {
        String uri = String.format("/v1beta/models/%s:generateContent?key=%s", MODEL, geminiApiKey);

        JsonObject requestBody = buildRequestBody(prompt);
        log.debug("Calling Gemini with prompt length: {}", prompt.length());

        String response = webClient.post()
                .uri(uri)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .flatMap(err -> {
                                    log.error("Gemini API error: {}", err);
                                    return reactor.core.publisher.Mono.error(
                                            new RuntimeException("Gemini error: " + err));
                                }))
                .bodyToMono(String.class)
                .block();

        return extractText(response);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String buildClassificationPrompt(String transcript) {
        return "You are a sales lead classifier. Analyze this call transcript and classify the lead.\n\n" +
                "Classification rules:\n" +
                "- HOT: Strong interest, budget confirmed, wants to move forward quickly, asked for pricing/demo\n" +
                "- WARM: Some interest, needs more information, has potential objections but open to follow-up\n" +
                "- COLD: Not interested, no budget, wrong timing, or asked not to be contacted\n\n" +
                "Respond with ONLY one word: HOT, WARM, or COLD\n\n" +
                "Transcript:\n" + transcript;
    }

    private JsonObject buildRequestBody(String prompt) {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);

        JsonArray parts = new JsonArray();
        parts.add(part);

        JsonObject content = new JsonObject();
        content.add("parts", parts);

        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject body = new JsonObject();
        body.add("contents", contents);

        // Generation config
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature",     0.3);   // low temp for classification consistency
        genConfig.addProperty("maxOutputTokens", 512);
        body.add("generationConfig", genConfig);

        return body;
    }

    private String extractText(String responseJson) {
        try {
            JsonObject root       = JsonParser.parseString(responseJson).getAsJsonObject();
            JsonArray  candidates = root.getAsJsonArray("candidates");
            JsonObject candidate  = candidates.get(0).getAsJsonObject();
            JsonObject content    = candidate.getAsJsonObject("content");
            JsonArray  parts      = content.getAsJsonArray("parts");
            return parts.get(0).getAsJsonObject().get("text").getAsString().trim();
        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", responseJson, e);
            return "";
        }
    }

    private LeadClassification parseClassification(String raw) {
        String upper = raw.toUpperCase().trim();
        if (upper.contains("HOT"))  return LeadClassification.HOT;
        if (upper.contains("WARM")) return LeadClassification.WARM;
        return LeadClassification.COLD;
    }

    // ─── Enum ─────────────────────────────────────────────────────────────────

    public enum LeadClassification {
        HOT, WARM, COLD
    }
}