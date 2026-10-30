package com.elevatebox.agent.service;

import com.elevatebox.agent.util.Logs;
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
import java.time.Duration;

/**
 * Sends WhatsApp text messages through the Meta WhatsApp Cloud API (Graph API v20.0).
 *
 * <p>Used at three points of the call flow: a heads-up message before the call, an
 * optional message during the call, and a follow-up after the call. It also sends a
 * call report to the sales team once the lead has been classified (see
 * {@link GeminiService.LeadClassification}).
 *
 * <p><b>Sandbox limitation:</b> the Meta sandbox only delivers to recipients who have
 * opted in, and free-form text is only delivered inside Meta's 24-hour window. A
 * recipient who has not opted in will not receive the message.
 *
 * <p>Credentials and the recipient number come from configuration
 * ({@code meta.whatsapp.token}, {@code meta.whatsapp.phone-number-id} and
 * {@code meta.whatsapp.target-phone}) and are never hard-coded.
 *
 * <p>Every send method returns the WhatsApp message ID and throws a
 * {@link RuntimeException} on failure; nothing is retried here.
 */
@Slf4j
@Service
public class WhatsAppService {

    /**
     * Recipient of all lead-facing messages (pre-call, mid-call and post-call) and the
     * lead number printed in sales-team reports. Read from the
     * {@code meta.whatsapp.target-phone} property (environment variable
     * {@code WHATSAPP_TARGET_PHONE}); there is deliberately no default.
     *
     * <p>Until the app is live this should be a verified test number, because the Meta
     * sandbox only delivers to opted-in numbers.
     */
    @Value("${meta.whatsapp.target-phone}")
    private String targetPhone;

    /** Meta access token, read from the {@code meta.whatsapp.token} property. */
    @Value("${meta.whatsapp.token}")
    private String accessToken;

    /** ID of the WhatsApp sender number, read from {@code meta.whatsapp.phone-number-id}. */
    @Value("${meta.whatsapp.phone-number-id}")
    private String phoneNumberId;

    /** Shared HTTP client used for all calls to the Meta API; created in {@link #init()}. */
    private HttpClient httpClient;

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Creates the shared {@link HttpClient} once Spring has injected the configuration,
     * and logs which sender number is in use.
     */
    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(SEND_TIMEOUT).build();
        log.info("Meta WhatsApp Cloud API initialized — Phone Number ID: {}", phoneNumberId);
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Sends the fixed heads-up message that tells the lead a call is about to start.
     *
     * @return the WhatsApp message ID returned by Meta
     * @throws RuntimeException if the send fails (see {@link #sendMessage(String, String)})
     */
    public String sendPreCallMessage() {
        String body = "Hi! 👋 This is ElevateBox. We're about to give you a quick call in a few minutes. "
                + "Looking forward to connecting with you!";
        log.info("Sending pre-call WhatsApp to {}", Logs.maskPhone(targetPhone));
        return sendMessage(targetPhone, body);
    }

    /**
     * Sends a custom message to the lead while the call is in progress.
     *
     * @param messageBody the text to send
     * @return the WhatsApp message ID returned by Meta
     * @throws RuntimeException if the send fails (see {@link #sendMessage(String, String)})
     */
    public String sendMidCallMessage(String messageBody) {
        return sendMidCallMessage(null, messageBody);
    }

    /**
     * Sends a message during the call to a specific number, for example the contact who asked
     * for the call.
     *
     * @param to          the recipient in international format, or null for the configured
     *                    target number
     * @param messageBody the text to send
     * @return the WhatsApp message ID returned by Meta
     */
    public String sendMidCallMessage(String to, String messageBody) {
        String recipient = recipientOrTarget(to);
        log.info("Sending mid-call WhatsApp to {}", Logs.maskPhone(recipient));
        return sendMessage(recipient, messageBody);
    }

    /**
     * Sends a custom message to the lead after the call has ended, for example the
     * callback confirmation sent by {@link CallbackService}.
     *
     * @param messageBody the text to send
     * @return the WhatsApp message ID returned by Meta
     * @throws RuntimeException if the send fails (see {@link #sendMessage(String, String)})
     */
    public String sendPostCallMessage(String messageBody) {
        return sendPostCallMessage(null, messageBody);
    }

    /**
     * Sends a message after the call to a specific number.
     *
     * @param to          the recipient in international format, or null for the configured
     *                    target number
     * @param messageBody the text to send
     * @return the WhatsApp message ID returned by Meta
     */
    public String sendPostCallMessage(String to, String messageBody) {
        String recipient = recipientOrTarget(to);
        log.info("Sending post-call WhatsApp to {}", Logs.maskPhone(recipient));
        return sendMessage(recipient, messageBody);
    }

    /**
     * Replies to someone who messaged the business, for example to confirm their call request.
     *
     * @param to          the recipient in international format
     * @param messageBody the text to send
     * @return the WhatsApp message ID returned by Meta
     */
    public String sendMessageTo(String to, String messageBody) {
        log.info("Sending WhatsApp reply to {}", Logs.maskPhone(to));
        return sendMessage(to, messageBody);
    }

    private String recipientOrTarget(String to) {
        return to == null || to.isBlank() ? targetPhone : to;
    }

    /**
     * Sends a call report to the sales team: the lead's number, the classification with
     * an emoji (hot, warm or cold) and a short summary of the call.
     *
     * @param salesTeamNumber the sales team's WhatsApp number, in international format
     * @param classification  how the lead was classified after the call
     * @param summary         short summary of the call
     * @return the WhatsApp message ID returned by Meta
     * @throws RuntimeException if the send fails (see {@link #sendMessage(String, String)})
     */
    public String notifySalesTeam(String salesTeamNumber,
                                  GeminiService.LeadClassification classification,
                                  String summary) {
        String body = String.format(
                "📞 *ElevateBox Call Report*\n\n"
                + "Lead: %s\n"
                + "Classification: %s %s\n\n"
                + "Summary:\n%s",
                targetPhone,
                classificationEmoji(classification),
                classification.name(),
                summary);
        log.info("Notifying sales team at {} — lead is {}", Logs.maskPhone(salesTeamNumber), classification);
        return sendMessage(salesTeamNumber, body);
    }

    // ─── Core send — Meta WhatsApp Cloud API ─────────────────────────────────

    /**
     * Sends one plain-text WhatsApp message through the Meta Cloud API.
     *
     * <p>Builds the JSON payload, posts it to the {@code /messages} endpoint of the
     * configured sender number with the bearer token, then reads the message ID from the
     * first entry of the {@code messages} array in the response.
     *
     * <p>This call is blocking, gives up after {@link #SEND_TIMEOUT} and is not retried. The
     * timeout matters for the mid-call tool, which answers while the customer is on the line.
     *
     * @param toNumber the recipient's number in international format
     * @param body     the message text
     * @return the WhatsApp message ID returned by Meta
     * @throws RuntimeException if Meta answers with an HTTP status of 400 or above (the
     *                          response body is included in the message), if the request
     *                          cannot be sent, or if the response does not have the
     *                          expected shape
     */
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
                    .timeout(SEND_TIMEOUT)
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
            log.error("Failed to send WhatsApp to {}: {}", Logs.maskPhone(toNumber), e.getMessage(), e);
            throw new RuntimeException("WhatsApp send failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a lead classification to the emoji shown in the sales report.
     *
     * @param c the lead classification
     * @return a fire for hot, a thermometer for warm, a snowflake for cold
     */
    private String classificationEmoji(GeminiService.LeadClassification c) {
        return switch (c) {
            case HOT  -> "🔥";
            case WARM -> "🌡️";
            case COLD -> "❄️";
            case UNKNOWN -> "❔";
        };
    }
}