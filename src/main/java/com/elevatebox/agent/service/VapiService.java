package com.elevatebox.agent.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class VapiService {

    private static final String VAPI_BASE_URL = "https://api.vapi.ai";
    private static final String TARGET_PHONE   = "+918688664337";

    @Value("${vapi.private-key}")
    private String vapiPrivateKey;

    @Value("${vapi.assistant-id:}")
    private String assistantId;     
    
    @Value("${twilio.account-sid}")
    private String twilioAccountSid;

    @Value("${twilio.auth-token}")
    private String twilioAuthToken;

    @Value("${twilio.phone-number:+17372508034}")
    private String twilioPhoneNumber;// optional — set in yml if you have a pre-built assistant

    private final WebClient webClient;
    private final Gson gson = new Gson();

    public VapiService(WebClient.Builder builder) {
        this.webClient = builder.baseUrl(VAPI_BASE_URL).build();
    }

    /**
     * Initiates an outbound call to the target number via Vapi.
     * Returns the Vapi call object JSON as a String.
     */
    public String initiateCall() {
        JsonObject body = buildCallPayload();
        log.info("Initiating Vapi outbound call to {}", TARGET_PHONE);

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
                .doOnNext(res -> log.info("Vapi call created: {}", res))
                .block();

        return response;
    }

    /**
     * Fetches call details by callId — useful for polling status or retrieving transcript.
     */
    public String getCall(String callId) {
        return webClient.get()
                .uri("/call/" + callId)
                .header("Authorization", "Bearer " + vapiPrivateKey)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(res -> log.info("Vapi call details for {}: {}", callId, res))
                .block();
    }

    // ─── Private helpers ────────────────────────────────────────────────────────

    private JsonObject buildCallPayload() {
        JsonObject payload = new JsonObject();

        // Phone number — Twilio credentials passed directly to Vapi
        // Vapi will use Twilio to make the outbound call
        JsonObject phoneNumber = new JsonObject();
        phoneNumber.addProperty("twilioPhoneNumber",  twilioPhoneNumber);
        phoneNumber.addProperty("twilioAccountSid",   twilioAccountSid);
        phoneNumber.addProperty("twilioAuthToken",    twilioAuthToken);
        payload.add("phoneNumber", phoneNumber);

        payload.add("customer", buildCustomer());

        // If you have a saved Vapi assistant use its ID, otherwise inline the assistant config
        if (assistantId != null && !assistantId.isBlank()) {
            payload.addProperty("assistantId", assistantId);
        } else {
            payload.add("assistant", buildInlineAssistant());
        }

        return payload;
    }

    private JsonObject buildCustomer() {
        JsonObject customer = new JsonObject();
        customer.addProperty("number", TARGET_PHONE);
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