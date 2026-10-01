package com.elevatebox.agent.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Handles callback scheduling for leads who ask to be called back later.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>Extract a callback time from a call transcript by asking Gemini to
 *       turn natural speech ("call me tomorrow morning") into a concrete
 *       date-time.</li>
 *   <li>Send the lead a WhatsApp message confirming that callback time.</li>
 * </ol>
 *
 * <p>Used in the post-call pipeline after a lead is classified (typically
 * WARM). Depends on {@link GeminiService} for time extraction and
 * {@link WhatsAppService} for the confirmation message.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final WhatsAppService whatsAppService;
    private final GeminiService geminiService;

    // ─── Callback Scheduling from Speech ─────────────────────────────────────

    /**
     * Extracts a callback time from a call transcript using Gemini.
     *
     * <p>The prompt includes the current date and time so relative phrases like
     * "tomorrow" or "Monday morning" resolve to an absolute date-time, e.g.
     * "call me back tomorrow morning" becomes "2026-09-13 10:00". Gemini is told
     * to reply with JSON only; any markdown code fences around the reply are
     * stripped before parsing.
     *
     * <p>This method never throws. If Gemini finds no callback time, or its
     * response cannot be parsed, it returns a result with {@code found = false}
     * and null time fields.
     *
     * @param transcript the full call transcript to search for a callback request
     * @return a {@link CallbackResult} with {@code found = true} and the
     *         extracted times, or {@code found = false} if none was found or
     *         parsing failed
     */
    public CallbackResult scheduleCallback(String transcript) {
        log.info("Extracting callback time from transcript...");

        String prompt = "Extract a callback time from this sales call transcript.\n\n" +
                "Today is " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm")) + ".\n\n" +
                "Transcript:\n" + transcript + "\n\n" +
                "If the person mentioned a callback time (e.g. 'call me back tomorrow', 'evening is fine', " +
                "'call me Monday morning'), extract it and return ONLY a JSON like this:\n" +
                "{\"found\": true, \"datetime\": \"2026-09-13 10:00\", \"human\": \"tomorrow morning at 10 AM\"}\n\n" +
                "If no callback time was mentioned, return:\n" +
                "{\"found\": false}\n\n" +
                "Return ONLY the JSON, nothing else.";

        String response = geminiService.callGemini(prompt);
        log.info("Callback extraction response: {}", response);

        try {
            // Clean response
            String clean = response.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonObject json = JsonParser.parseString(clean).getAsJsonObject();

            boolean found = json.get("found").getAsBoolean();
            if (!found) {
                return new CallbackResult(false, null, null);
            }

            String datetime = json.get("datetime").getAsString();
            String human    = json.get("human").getAsString();

            log.info("Callback scheduled: {} ({})", human, datetime);
            return new CallbackResult(true, datetime, human);

        } catch (Exception e) {
            log.error("Failed to parse callback response: {}", e.getMessage());
            return new CallbackResult(false, null, null);
        }
    }

    /**
     * Sends a WhatsApp message confirming the callback time to the lead.
     *
     * <p>The message is sent through {@link WhatsAppService#sendPostCallMessage(String)}.
     *
     * @param callbackHuman the callback time in plain language, as returned in
     *                      {@link CallbackResult#humanReadable()} (e.g. "tomorrow
     *                      morning at 10 AM")
     */
    public void sendCallbackConfirmation(String callbackHuman) {
        String message = "Hi! Just confirming — I'll call you back " + callbackHuman + ". " +
                "Looking forward to connecting again! 😊\n\n" +
                "— ElevateBox Team";
        whatsAppService.sendPostCallMessage(message);
        log.info("Callback confirmation sent for: {}", callbackHuman);
    }

    /**
     * Result of a callback time extraction.
     *
     * @param found         whether a callback time was found in the transcript
     * @param datetime      the extracted time as {@code yyyy-MM-dd HH:mm}, or
     *                      null if {@code found} is false
     * @param humanReadable the time in plain language (e.g. "tomorrow morning at
     *                      10 AM"), or null if {@code found} is false
     */
    public record CallbackResult(boolean found, String datetime, String humanReadable) {}
}