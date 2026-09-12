package com.elevatebox.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeadClassifierService {

    private final GeminiService        geminiService;
    private final WhatsAppService      whatsAppService;
    private final FollowUpService      followUpService;
    private final CallbackService      callbackService;

    /**
     * Full post-call pipeline:
     * 1. Classify lead HOT/WARM/COLD
     * 2. Send post-call WhatsApp with context + resume + number + build image
     * 3. Check if callback was requested → send confirmation
     */
    public LeadResult processPostCall(String transcript) {
        log.info("Starting post-call pipeline...");

        // Step 1: classify
        GeminiService.LeadClassification classification = geminiService.classifyLead(transcript);
        log.info("Lead classified as: {}", classification);

        // Step 2: post-call WhatsApp — resume + number + build image + context
        followUpService.sendPostCallFollowUp(transcript, classification);

        // Step 3: callback scheduling
        CallbackService.CallbackResult callback = callbackService.scheduleCallback(transcript);
        if (callback.found()) {
            log.info("Callback detected: {}", callback.humanReadable());
            callbackService.sendCallbackConfirmation(callback.humanReadable());
        }

        // Step 4: summarize
        String summary = geminiService.summarizeConversation(transcript);

        LeadResult result = new LeadResult(classification, summary,
                callback.found(), callback.humanReadable());
        log.info("Post-call pipeline complete: {}", result);
        return result;
    }

    /**
     * Mid-call pipeline — triggered by HOT intent keywords during live call.
     */
    public String processMidCall(String partialTranscript) {
        log.info("Processing mid-call WhatsApp trigger...");

        // Only fire if HOT intent detected
        GeminiService.LeadClassification classification =
                geminiService.classifyLead(partialTranscript);

        if (classification == GeminiService.LeadClassification.HOT) {
            String midCallMessage = geminiService.generateMidCallMessage(partialTranscript);
            String sid = whatsAppService.sendMidCallMessage(midCallMessage);
            log.info("Mid-call WhatsApp sent — SID: {}", sid);
            return sid;
        }

        log.info("Mid-call: intent not HOT ({}), skipping WhatsApp", classification);
        return "NOT_HOT_INTENT";
    }

    /**
     * Quick classify only — no side effects.
     */
    public GeminiService.LeadClassification quickClassify(String transcript) {
        return geminiService.classifyLead(transcript);
    }

    public record LeadResult(
            GeminiService.LeadClassification classification,
            String summary,
            boolean callbackScheduled,
            String callbackTime
    ) {}
}