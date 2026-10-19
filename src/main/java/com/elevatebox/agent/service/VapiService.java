package com.elevatebox.agent.service;

import com.elevatebox.agent.util.Logs;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
@Service
public class VapiService {

    private static final String VAPI_BASE_URL = "https://api.vapi.ai";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Value("${vapi.target-phone}")
    private String targetPhone;

    @Value("${vapi.private-key}")
    private String vapiPrivateKey;

    /**
     * ID of the phone number imported into Vapi (Dashboard → Phone Numbers).
     * Vapi already holds the Twilio credentials for it, so none are sent per call.
     */
    @Value("${vapi.phone-number-id}")
    private String phoneNumberId;

    /** Optional ID of a saved Vapi assistant; when blank the inline assistant below is used. */
    @Value("${vapi.assistant-id:}")
    private String assistantId;

    private final WebClient webClient;
    private final Gson gson = new Gson();

    public VapiService(WebClient.Builder builder) {
        this.webClient = builder.baseUrl(VAPI_BASE_URL).build();
    }

    /**
     * Initiates an outbound call to the target number via Vapi.
     * Returns the Vapi call object JSON as a String.
     */
    /**
 * Initiates an outbound call to the target phone number via Vapi API.
 * Builds the call payload with assistant config and customer details,
 * then sends it to the Vapi /call/phone endpoint.
 *
 * @return Vapi API response as JSON string containing call ID and status
 * @throws RuntimeException if Vapi API returns an error response
 */
    public String initiateCall() {
        JsonObject body = buildCallPayload();
        log.info("Initiating Vapi outbound call to {}", Logs.maskPhone(targetPhone));

        String response = webClient.post()
                .uri("/call/phone")
                .header("Authorization", "Bearer " + vapiPrivateKey)
                .header("Content-Type", "application/json")
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .flatMap(err -> {
                                    log.error("Vapi call failed [{}]: {}", clientResponse.statusCode(), err);
                                    return Mono.error(new RuntimeException("Vapi error: " + err));
                                }))
                .bodyToMono(String.class)
                .doOnNext(res -> log.info("Vapi call created: id={}", extractCallId(res)))
                .block(TIMEOUT);

        return response;
    }

    /**
     * Fetches call details by callId — useful for polling status or retrieving transcript.
     */
    /**
 * Fetches call details by callId from Vapi API.
 * Useful for polling call status or retrieving transcript after call ends.
 *
 * @param callId the unique Vapi call identifier
 * @return Vapi call details as JSON string
 */
    public String getCall(String callId) {
        return webClient.get()
                .uri("/call/" + callId)
                .header("Authorization", "Bearer " + vapiPrivateKey)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(res -> log.info("Fetched Vapi call details for {}", callId))
                .block(TIMEOUT);
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    private JsonObject buildCallPayload() {
        JsonObject payload = new JsonObject();

        // The number to call FROM: an imported Twilio number that Vapi already has credentials for
        payload.addProperty("phoneNumberId", phoneNumberId);

        payload.add("customer", buildCustomer());

        // If you have a saved Vapi assistant use its ID, otherwise inline the assistant config
        if (assistantId != null && !assistantId.isBlank()) {
            payload.addProperty("assistantId", assistantId);
        } else {
            payload.add("assistant", buildInlineAssistant());
        }

        return payload;
    }

    /** Reads the call ID out of a Vapi response so logs never need to contain the full body. */
    private String extractCallId(String responseJson) {
        try {
            return JsonParser.parseString(responseJson).getAsJsonObject().get("id").getAsString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private JsonObject buildCustomer() {
        JsonObject customer = new JsonObject();
        customer.addProperty("number", targetPhone);
        customer.addProperty("name",   "ElevateBox Lead");
        return customer;
    }

    private JsonObject buildInlineAssistant() {
        JsonObject assistant = new JsonObject();
        assistant.addProperty("name", "ElevateBox AI Agent");

        // First message the agent says when the call connects
        assistant.addProperty("firstMessage",
                "Hi! This is ElevateBox calling. I wanted to quickly connect with you about our services. " +
                "Do you have two minutes?");

        // Model — systemPrompt goes INSIDE model, not at assistant level
        JsonObject model = new JsonObject();
        model.addProperty("provider", "google");
        model.addProperty("model",    "gemini-2.0-flash-lite");
        model.addProperty("systemPrompt",
                "You are a professional sales agent for ElevateBox, an AI-powered business solutions company. " +
                "Your goal is to qualify leads by understanding their business needs, budget, and timeline. " +
                "Ask open-ended questions, listen actively, and classify the lead as HOT (ready to buy), " +
                "WARM (interested but needs nurturing), or COLD (not interested). " +
                "Be concise, friendly, and never pushy. If the prospect is not interested, thank them politely.");
        assistant.add("model", model);

        return assistant;
    }
}