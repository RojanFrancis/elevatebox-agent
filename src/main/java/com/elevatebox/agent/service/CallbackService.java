package com.elevatebox.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Confirms a callback request to the lead over WhatsApp.
 *
 * <p>Detecting that a lead asked to be called back, and when, is part of the single Gemini
 * analysis in {@link GeminiService#analyzeCall(String)}. This class only sends the confirmation.
 * It does not schedule the callback itself: that needs a calendar or job scheduler, which is
 * on the roadmap, not built.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final WhatsAppService whatsAppService;

    /**
     * Sends a WhatsApp message confirming the callback time to the lead.
     *
     * @param callbackHuman the callback time in the lead's own words (for example
     *                      "tomorrow morning")
     */
    public void sendCallbackConfirmation(String callbackHuman) {
        sendCallbackConfirmation(callbackHuman, null);
    }

    /**
     * Same as {@link #sendCallbackConfirmation(String)}, sent to a specific number.
     *
     * @param recipient the person who was on the call, or null for the configured target number
     */
    public void sendCallbackConfirmation(String callbackHuman, String recipient) {
        String message = "Hi! Just confirming — I'll call you back " + callbackHuman + ". " +
                "Looking forward to connecting again! 😊\n\n" +
                "— ElevateBox Team";
        whatsAppService.sendPostCallMessage(recipient, message);
        log.info("Callback confirmation sent for: {}", callbackHuman);
    }
}
