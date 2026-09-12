package com.elevatebox.agent.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@Service
public class WhatsAppService {

    // During testing: your verified recipient number
    // After production setup: change to "+918688664337" (the actual lead)
    private static final String TARGET_PHONE = "+919514971623";

    @Value("${meta.whatsapp.token}")
    private String accessToken;

    @Value("${meta.whatsapp.phone-number-id}")
    private String phoneNumberId;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newHttpClient();
        log.info("Meta WhatsApp Cloud API initialized — Phone Number ID: {}", phoneNumberId);
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    public String sendPreCallMessage() {
        String body = "Hi! 👋 This is ElevateBox. We're about to give you a quick call in a few minutes. "
                + "Looking forward to connecting with you!";
        log.info("Sending pre-call WhatsApp to {}", TARGET_PHONE);
        return sendMessage(TARGET_PHONE, body);
    }

    public String sendMidCallMessage(String messageBody) {
        log.info("Sending mid-call WhatsApp to {}", TARGET_PHONE);
        return sendMessage(TARGET_PHONE, messageBody);
    }

    public String sendPostCallMessage(String messageBody) {
        log.info("Sending post-call WhatsApp to {}", TARGET_PHONE);
        return sendMessage(TARGET_PHONE, messageBody);
    }

    public String notifySalesTeam(String salesTeamNumber,
                                  GeminiService.LeadClassification classification,
                                  String summary) {
        String body = String.format(
                "📞 *ElevateBox Call Report*\n\n"
                + "Lead: %s\n"
                + "Classification: %s %s\n\n"
                + "Summary:\n%s",
                TARGET_PHONE,
                classificationEmoji(classification),
                classification.name(),
                summary);
        log.info("Notifying sales team at {} — lead is {}", salesTeamNumber, classification);
        return sendMessage(salesTeamNumber, body);
    }

    // ─── Core send — Meta WhatsApp Cloud API ─────────────────────────────────

    private String sendMessage(String toNumber, String body) {
        try {
            String url = "https://graph.facebook.com/v20.0/" + phoneNumberId + "/messages";

            // Build JSON payload
            JsonObject payload = new JsonObject();
            payload.addProperty("messaging_product", "whatsapp");
            payload.addProperty("to", toNumber);
            payload.addProperty("type", "text");

            JsonObject text = new JsonObject();
            text.addProperty("body", body);
            payload.add("text", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.error("Meta API error {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("WhatsApp send failed: " + response.body());
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String msgId = json.getAsJsonArray("messages")
                    .get(0).getAsJsonObject()
                    .get("id").getAsString();

            log.info("WhatsApp sent via Meta — Message ID: {}", msgId);
            return msgId;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to send WhatsApp to {}: {}", toNumber, e.getMessage(), e);
            throw new RuntimeException("WhatsApp send failed: " + e.getMessage(), e);
        }
    }

    private String classificationEmoji(GeminiService.LeadClassification c) {
        return switch (c) {
            case HOT  -> "🔥";
            case WARM -> "🌡️";
            case COLD -> "❄️";
        };
    }
}