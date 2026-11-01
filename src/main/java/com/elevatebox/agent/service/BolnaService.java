package com.elevatebox.agent.service;

import com.elevatebox.agent.util.Logs;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Places calls through Bolna, an Indian voice-AI platform with its own Indian numbers.
 *
 * <p>The agent itself (voice, prompt, the {@code send_whatsapp} tool, the maximum call length
 * and the webhook) is configured in Bolna's dashboard; this class only asks it to call someone.
 * The customer's name goes in {@code user_data}, so the agent's prompt can use
 * {@code {customer_name}}. Bolna reports the result to {@code POST /api/call/bolna/callback}.
 */
@Slf4j
@Service
public class BolnaService {

    private static final String BOLNA_BASE_URL = "https://api.bolna.ai";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebClient webClient;
    private final String apiKey;
    private final String agentId;
    private final String fromNumber;

    public BolnaService(WebClient.Builder builder,
                        @Value("${bolna.api-key:}") String apiKey,
                        @Value("${bolna.agent-id:}") String agentId,
                        @Value("${bolna.from-number:}") String fromNumber) {
        this.webClient  = builder.baseUrl(BOLNA_BASE_URL).build();
        this.apiKey     = apiKey;
        this.agentId    = agentId;
        this.fromNumber = fromNumber;
    }

    /**
     * Asks Bolna to call a number.
     *
     * @return Bolna's execution ID for the call, or "unknown" if the response had none
     * @throws RuntimeException if Bolna is not configured or refuses the call
     */
    public String initiateCall(String number, String name) {
        if (apiKey.isBlank() || agentId.isBlank()) {
            throw new IllegalStateException("Set BOLNA_API_KEY and BOLNA_AGENT_ID to call through Bolna");
        }
        log.info("Initiating Bolna outbound call to {}", Logs.maskPhone(number));

        String response = webClient.post()
                .uri("/call")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(payload(number, name).toString())
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .flatMap(err -> {
                                    log.error("Bolna call failed [{}]: {}", clientResponse.statusCode(), err);
                                    return Mono.error(new RuntimeException("Bolna error: " + err));
                                }))
                .bodyToMono(String.class)
                .block(TIMEOUT);

        String executionId = executionId(response);
        log.info("Bolna call queued: executionId={}", executionId);
        return executionId;
    }

    JsonObject payload(String number, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("agent_id", agentId);
        body.addProperty("recipient_phone_number", number);
        if (!fromNumber.isBlank()) {
            body.addProperty("from_phone_number", fromNumber);
        }
        JsonObject userData = new JsonObject();
        userData.addProperty("customer_name", name == null || name.isBlank() ? "there" : name);
        body.add("user_data", userData);
        return body;
    }

    static String executionId(String responseJson) {
        try {
            return JsonParser.parseString(responseJson).getAsJsonObject().get("execution_id").getAsString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
