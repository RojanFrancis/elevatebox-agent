package com.elevatebox.agent.service;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Thin client around Google's Gemini {@code generateContent} REST API.
 *
 * <p>Used by the call pipeline to turn a call transcript into something useful:
 * a HOT/WARM/COLD lead classification, a short call summary, and WhatsApp
 * messages (post-call follow-up and mid-call). Every feature builds a text
 * prompt and sends it through {@link #callGemini(String)}.
 *
 * <p>Configuration: the API key is read from the {@code gemini.api-key}
 * property. The model is fixed in code via {@code MODEL}.
 *
 * <p>All calls are <b>blocking</b> (the reactive {@link WebClient} result is
 * awaited with {@code block()}), so do not call these methods from a
 * non-blocking (reactive) thread.
 */
@Slf4j
@Service
public class GeminiService {

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String MODEL = "gemini-3.6-flash";

    /** Gemini API key, injected from the {@code gemini.api-key} property. */
    @Value("${gemini.api-key}")
    private String geminiApiKey;

    private final WebClient webClient;
    private final Gson gson = new Gson();

    /**
     * Creates the service with a {@link WebClient} pointed at the Gemini base URL.
     *
     * @param builder Spring-provided {@link WebClient.Builder}; the base URL is set here
     */
    public GeminiService(WebClient.Builder builder) {
        this.webClient = builder.baseUrl(GEMINI_BASE_URL).build();
    }

    // ─── Lead Classification ─────────────────────────────────────────────────

    /**
     * Classifies a lead as HOT, WARM or COLD from the full call transcript.
     *
     * <p>The model is asked to answer with a single word. The reply is then
     * matched loosely: if it contains "HOT" the result is {@code HOT}, else if
     * it contains "WARM" the result is {@code WARM}, otherwise {@code COLD}.
     * That means an empty or unparseable model reply also ends up as
     * {@code COLD}, so a parsing failure is indistinguishable from a genuinely
     * cold lead.
     *
     * @param transcript the full call transcript
     * @return the classification; {@code COLD} when the reply is empty or unrecognised
     * @throws RuntimeException if the Gemini API returns an HTTP error status
     */
    public LeadClassification classifyLead(String transcript) {
        String prompt = buildClassificationPrompt(transcript);
        String raw    = callGemini(prompt);
        return parseClassification(raw);
    }

    /**
     * Generates a short post-call summary (3-4 sentences) covering the
     * prospect's main interest, any objections, and agreed next steps.
     *
     * @param transcript the full call transcript
     * @return the summary text, or an empty string if the response could not be parsed
     * @throws RuntimeException if the Gemini API returns an HTTP error status
     */
    public String summarizeConversation(String transcript) {
        String prompt = "Summarize the following sales call transcript in 3-4 sentences. " +
                "Highlight: what the prospect's main interest was, any objections raised, and agreed next steps.\n\n" +
                "Transcript:\n" + transcript;
        return callGemini(prompt);
    }

    /**
     * Generates a personalised WhatsApp follow-up message to send after the call.
     *
     * <p>The prompt asks for a short, conversational message (max 3 sentences)
     * that thanks the lead, references something specific from the call, and
     * proposes a next step suited to the lead's classification.
     *
     * @param transcript     the full call transcript
     * @param classification the lead's classification, used to tailor the next step
     * @return the message text, or an empty string if the response could not be parsed
     * @throws RuntimeException if the Gemini API returns an HTTP error status
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
     * Generates a very short (1-2 sentence) WhatsApp message to send while the
     * call is still in progress, for example to share a helpful link.
     *
     * <p>The prompt asks the model to include the literal placeholder
     * {@code [LINK]}. The caller is responsible for replacing it with a real
     * URL before sending.
     *
     * @param partialTranscript the transcript of the call so far
     * @return the message text, or an empty string if the response could not be parsed
     * @throws RuntimeException if the Gemini API returns an HTTP error status
     */
    public String generateMidCallMessage(String partialTranscript) {
        String prompt = "Based on this partial sales call transcript, write a very short WhatsApp message " +
                "(1-2 sentences) to send to the prospect RIGHT NOW while on the call. " +
                "It should reinforce what's being discussed and include a helpful link placeholder [LINK].\n\n" +
                "Transcript so far:\n" + partialTranscript;
        return callGemini(prompt);
    }

    // ─── Core Gemini API call ─────────────────────────────────────────────────

    /**
     * Sends a single-turn text prompt to the Gemini {@code generateContent}
     * endpoint and returns the model's text reply.
     *
     * <p>The request uses temperature 0.3 (for consistent output) and a cap of
     * 512 output tokens. The API key is passed as a query parameter on the
     * request URL. The call blocks until the response arrives; no timeout or
     * retry is configured here.
     *
     * <p>Only the first part of the first candidate is returned, trimmed. If
     * the response cannot be parsed (for example, no candidates were returned),
     * the error is logged and an empty string is returned rather than an
     * exception being thrown.
     *
     * @param prompt the prompt text to send
     * @return the trimmed reply text, or an empty string if parsing fails
     * @throws RuntimeException if the API responds with an HTTP error status
     *                          (the response body is logged and included in the message)
     */
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

    /**
     * Builds the classification prompt: the HOT/WARM/COLD rules, an instruction
     * to reply with exactly one word, and the transcript.
     */
    private String buildClassificationPrompt(String transcript) {
        return "You are a sales lead classifier. Analyze this call transcript and classify the lead.\n\n" +
                "Classification rules:\n" +
                "- HOT: Strong interest, budget confirmed, wants to move forward quickly, asked for pricing/demo\n" +
                "- WARM: Some interest, needs more information, has potential objections but open to follow-up\n" +
                "- COLD: Not interested, no budget, wrong timing, or asked not to be contacted\n\n" +
                "Respond with ONLY one word: HOT, WARM, or COLD\n\n" +
                "Transcript:\n" + transcript;
    }

    /**
     * Builds the JSON request body in the shape Gemini expects:
     * {@code contents[0].parts[0].text} plus a {@code generationConfig} with
     * temperature 0.3 and {@code maxOutputTokens} 512.
     */
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

    /**
     * Extracts the reply text from a Gemini response, reading
     * {@code candidates[0].content.parts[0].text}.
     *
     * @param responseJson the raw JSON response body
     * @return the trimmed text, or an empty string if the JSON is missing or
     *         does not have the expected structure (the failure is logged)
     */
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

    /**
     * Maps the model's raw reply to a {@link LeadClassification} using a
     * case-insensitive "contains" check, in the order HOT, WARM, then COLD as
     * the default.
     */
    private LeadClassification parseClassification(String raw) {
        String upper = raw.toUpperCase().trim();
        if (upper.contains("HOT"))  return LeadClassification.HOT;
        if (upper.contains("WARM")) return LeadClassification.WARM;
        return LeadClassification.COLD;
    }

    // ─── Enum ─────────────────────────────────────────────────────────────────

    /** How promising a lead is, as judged from the call transcript. */
    public enum LeadClassification {
        /** Strong interest and ready to move forward (pricing or demo asked). */
        HOT,
        /** Some interest but needs more information or has objections. */
        WARM,
        /** Not interested, no budget, wrong timing, or asked not to be contacted. */
        COLD
    }
}