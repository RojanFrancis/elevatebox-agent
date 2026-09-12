package com.elevatebox.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeadClassifierService {

    private final GeminiService geminiService;
    private final WhatsAppService whatsAppService;

    /**
     * Full post-call pipeline:
     * 1. Classify the lead from transcript
     * 2. Summarize the conversation
     * 3. Generate a tailored follow-up WhatsApp message
     * 4. Send it to the lead
     * 5. Return the full result
     */
    public LeadResult processPostCall(String transcript) {
        log.info("Starting post-call lead processing...");

        // Step 1: classify
        GeminiService.LeadClassification classification = geminiService.classifyLead(transcript);
        log.info("Lead classified as: {}", classification);

        // Step 2: summarize
        String summary = geminiService.summarizeConversation(transcript);
        log.info("Conversation summarized");

        // Step 3: generate WhatsApp follow-up
        String followUpMessage = geminiService.generateFollowUpMessage(transcript, classification);
        log.info("Follow-up message generated");

        // Step 4: send WhatsApp to lead
        String messageSid = whatsAppService.sendPostCallMessage(followUpMessage);
        log.info("Post-call WhatsApp sent — SID: {}", messageSid);

        // Step 5: build and return result
        LeadResult result = new LeadResult(classification, summary, followUpMessage, messageSid);
        log.info("Post-call pipeline complete: {}", result);
        return result;
    }

    /**
     * Mid-call pipeline — triggered while call is still live.
     * Sends a contextual WhatsApp (e.g. brochure link) based on partial transcript.
     */
    public String processMidCall(String partialTranscript) {
        log.info("Processing mid-call WhatsApp trigger...");

        String midCallMessage = geminiService.generateMidCallMessage(partialTranscript);
        String messageSid     = whatsAppService.sendMidCallMessage(midCallMessage);

        log.info("Mid-call WhatsApp sent — SID: {}", messageSid);
        return messageSid;
    }

    /**
     * Quick classify-only — no side effects, just returns HOT/WARM/COLD.
     * Useful for real-time scoring during the call.
     */
    public GeminiService.LeadClassification quickClassify(String transcript) {
        return geminiService.classifyLead(transcript);
    }

    // ─── Result record ────────────────────────────────────────────────────────

    public record LeadResult(
            GeminiService.LeadClassification classification,
            String summary,
            String followUpMessage,
            String whatsAppMessageSid
    ) {}
}