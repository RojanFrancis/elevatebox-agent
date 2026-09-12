package com.elevatebox.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowUpService {

    private final GeminiService geminiService;
    private final WhatsAppService whatsAppService;

    // Your details — go into the post-call WhatsApp
    @Value("${agent.name:Rojan Francis}")
    private String agentName;

    @Value("${agent.phone:+919514971623}")
    private String agentPhone;

    @Value("${agent.resume-link:https://github.com/RojanFrancis}")
    private String resumeLink;

    @Value("${agent.build-image-url:https://github.com/RojanFrancis/elevatebox-agent}")
    private String buildImageUrl;

    // ─── Post-call WhatsApp with all 4 required things ───────────────────────

    /**
     * Section 06 of assignment — send WhatsApp with:
     * 1. Context of the call (what they said, budget, timeline, features)
     * 2. Proper framing (written like a person, not a log)
     * 3. Your mobile number
     * 4. Image of how you built it (architecture link)
     * + Your resume
     */
    public void sendPostCallFollowUp(String transcript,
                                     GeminiService.LeadClassification classification) {
        log.info("Building post-call follow-up WhatsApp...");

        // Generate personalised context from the actual conversation
        String context = generateCallContext(transcript);

        // Build the full WhatsApp message
        String message = buildFollowUpMessage(context, classification);

        whatsAppService.sendPostCallMessage(message);
        log.info("Post-call follow-up sent — classification: {}", classification);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String generateCallContext(String transcript) {
        String prompt = "You just finished a sales call. Write a brief follow-up context paragraph " +
                "(3-4 sentences) based on this transcript.\n\n" +
                "Extract specifically: what the person sells, their budget if mentioned, " +
                "timeline if mentioned, and any specific features they asked about.\n\n" +
                "Write it as a human would write it after a real call — conversational, specific, " +
                "not like a log file. Reference what they actually said.\n\n" +
                "Transcript:\n" + transcript;

        return geminiService.callGemini(prompt);
    }

    private String buildFollowUpMessage(String context,
                                        GeminiService.LeadClassification classification) {
        String emoji = switch (classification) {
            case HOT  -> "🔥";
            case WARM -> "🌡️";
            case COLD -> "💙";
        };

        String nextStep = switch (classification) {
            case HOT  -> "I'd love to get started on a proposal for you. Can we schedule a quick 30-minute call?";
            case WARM -> "I'll follow up with you soon. Feel free to reach out anytime with questions.";
            case COLD -> "No pressure at all. I'll send you our portfolio in case it's useful down the line.";
        };

        return String.format(
                "Hi! Great speaking with you just now. %s\n\n" +
                "%s\n\n" +
                "%s\n\n" +
                "━━━━━━━━━━━━━━━\n" +
                "*About me:*\n" +
                "👤 %s\n" +
                "📱 %s\n" +
                "📄 Resume: %s\n\n" +
                "*How I built this system:*\n" +
                "🏗️ %s\n" +
                "Stack: Spring Boot + Vapi + Gemini AI + Meta WhatsApp API\n" +
                "━━━━━━━━━━━━━━━",
                emoji,
                context,
                nextStep,
                agentName,
                agentPhone,
                resumeLink,
                buildImageUrl
        );
    }
}