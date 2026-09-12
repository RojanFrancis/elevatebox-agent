package com.elevatebox.agent.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final WhatsAppService whatsAppService;
    private final GeminiService geminiService;

    // ─── Callback Scheduling from Speech ─────────────────────────────────────

    /**
     * Extract callback time from transcript using Gemini
     * e.g. "call me back tomorrow morning" → "2026-09-13 10:00"
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
     * Send a callback confirmation WhatsApp
     */
    public void sendCallbackConfirmation(String callbackHuman) {
        String message = "Hi! Just confirming — I'll call you back " + callbackHuman + ". " +
                "Looking forward to connecting again! 😊\n\n" +
                "— ElevateBox Team";
        whatsAppService.sendPostCallMessage(message);
        log.info("Callback confirmation sent for: {}", callbackHuman);
    }

    public record CallbackResult(boolean found, String datetime, String humanReadable) {}
}