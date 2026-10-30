package com.elevatebox.agent.service;

import com.elevatebox.agent.util.Logs;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.core.io.ClassPathResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

    /** Hard limit on call length, so a stuck or abusive call cannot run up the bill. */
    @Value("${vapi.max-call-seconds:180}")
    private int maxCallSeconds;

    /** The send_whatsapp tool definition; paste the same JSON into a saved assistant's tools. */
    static final String SEND_WHATSAPP_TOOL = "vapi/send-whatsapp-tool.json";

    private final WebClient webClient;

    public VapiService(WebClient.Builder builder) {
        this.webClient = builder.baseUrl(VAPI_BASE_URL).build();
    }

    /**
     * Initiates an outbound call to the target phone number through Vapi's /call/phone endpoint,
     * with the saved assistant if one is configured, otherwise the inline assistant.
     *
     * @return the ID of the created Vapi call, or "unknown" if the response had none
     * @throws RuntimeException if the Vapi API returns an error response
     */
    public String initiateCall() {
        return initiateCall(targetPhone, null);
    }

    /**
     * Calls a specific number, for example a contact who asked for a call on WhatsApp.
     *
     * @param number the number to call, in E.164
     * @param name   the customer's name for the assistant, or null
     * @return the ID of the created Vapi call, or "unknown" if the response had none
     * @throws RuntimeException if the Vapi API returns an error response
     */
    public String initiateCall(String number, String name) {
        JsonObject body = buildCallPayload(number, name);
        log.info("Initiating Vapi outbound call to {}", Logs.maskPhone(number));

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

        return extractCallId(response);
    }

    /**
     * Fetches call details by call ID, for polling the status or reading the transcript.
     *
     * @param callId the Vapi call ID
     * @return the Vapi call details as a JSON string
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

    private JsonObject buildCallPayload(String number, String name) {
        JsonObject payload = new JsonObject();

        // The number to call FROM: an imported Twilio number that Vapi already has credentials for
        payload.addProperty("phoneNumberId", phoneNumberId);

        payload.add("customer", buildCustomer(number, name));

        // If you have a saved Vapi assistant use its ID, otherwise inline the assistant config
        if (assistantId != null && !assistantId.isBlank()) {
            payload.addProperty("assistantId", assistantId);
            JsonObject overrides = new JsonObject();
            overrides.addProperty("maxDurationSeconds", maxCallSeconds);
            payload.add("assistantOverrides", overrides);
        } else {
            JsonObject assistant = buildInlineAssistant();
            assistant.addProperty("maxDurationSeconds", maxCallSeconds);
            payload.add("assistant", assistant);
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

    private JsonObject buildCustomer(String number, String name) {
        JsonObject customer = new JsonObject();
        customer.addProperty("number", number);
        customer.addProperty("name",   name == null || name.isBlank() ? "ElevateBox Lead" : name);
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
                "When the customer asks for pricing, examples or details, or asks for a WhatsApp, call the " +
                "send_whatsapp tool once, then tell them it is in their WhatsApp and carry on. " +
                "Be concise, friendly, and never pushy. If the prospect is not interested, thank them politely.");
        JsonArray tools = new JsonArray();
        tools.add(sendWhatsAppTool());
        model.add("tools", tools);
        assistant.add("model", model);

        return assistant;
    }

    /**
     * Loads the send_whatsapp tool. It has no server URL of its own, so Vapi sends the tool call
     * to the same webhook as every other event.
     */
    static JsonObject sendWhatsAppTool() {
        try (InputStream in = new ClassPathResource(SEND_WHATSAPP_TOOL).getInputStream()) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + SEND_WHATSAPP_TOOL, e);
        }
    }
}