package com.elevatebox.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends the personalised WhatsApp follow-up right after a voice call ends.
 *
 * <p>Flow: the call transcript goes to {@link GeminiService}, which writes a short,
 * human-sounding recap of what the lead said (what they sell, budget, timeline, features
 * they asked about). That recap is then wrapped in a message whose tone and call-to-action
 * depend on the lead's classification (HOT, WARM or COLD), signed with the agent's contact
 * details, and delivered through {@link WhatsAppService}.
 *
 * <p>The sender's name, phone number, resume link and architecture link are configurable
 * through the {@code agent.*} properties. The phone number is required and has no default,
 * so no personal number is baked into the code; the other values fall back to public links.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowUpService {

    private final GeminiService geminiService;
    private final WhatsAppService whatsAppService;

    /** Sender name shown in the "About me" block. Property: {@code agent.name}. */
    @Value("${agent.name:Rojan Francis}")
    private String agentName;

    /** Sender phone number shown in the message. Property: {@code agent.phone} (required). */
    @Value("${agent.phone}")
    private String agentPhone;

    /** Link to the sender's resume or profile. Property: {@code agent.resume-link}. */
    @Value("${agent.resume-link:https://github.com/RojanFrancis}")
    private String resumeLink;

    /** Link explaining how the system was built. Property: {@code agent.build-image-url}. */
    @Value("${agent.build-image-url:https://github.com/RojanFrancis/elevatebox-agent}")
    private String buildImageUrl;

    // ─── Post-call WhatsApp with all 4 required things ───────────────────────

    /**
     * Builds and sends the post-call WhatsApp message (Section 06 of the assignment).
     *
     * <p>The message contains:
     * <ol>
     *   <li>Context of the call, generated from the real conversation</li>
     *   <li>Human framing, written like a person rather than a log</li>
     *   <li>The sender's mobile number</li>
     *   <li>A link to how the system was built, plus the sender's resume</li>
     * </ol>
     *
     * @param transcript     full transcript of the finished call, used to generate the recap
     * @param classification lead temperature, which decides the emoji and the next-step text
     */
    public void sendPostCallFollowUp(String transcript,
                                     GeminiService.LeadClassification classification) {
        log.info("Building post-call follow-up WhatsApp...");

        // Generate personalised context from the actual conversation
        String context = generateCallContext(transcript);
        if (context == null || context.isBlank()) {
            context = "Thanks for taking my call just now. It was good to hear about your business.";
        }

        // Build the full WhatsApp message
        String message = buildFollowUpMessage(context, classification);

        whatsAppService.sendPostCallMessage(message);
        log.info("Post-call follow-up sent — classification: {}", classification);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Asks Gemini to turn the raw transcript into a short, conversational recap.
     *
     * <p>The prompt asks for 3-4 sentences covering what the lead sells, budget,
     * timeline and requested features, written as a person would after a real call.
     *
     * @param transcript full call transcript
     * @return the generated recap paragraph
     */
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

    /**
     * Assembles the final WhatsApp text from the recap, a classification-based next step,
     * and the sender's details.
     *
     * <p>Classification mapping: HOT proposes a 30-minute call, WARM promises a follow-up,
     * COLD keeps it low-pressure and offers a portfolio.
     *
     * @param context        recap paragraph from {@link #generateCallContext(String)}
     * @param classification lead temperature used to pick the emoji and next step
     * @return the formatted message, ready to send
     */
    private String buildFollowUpMessage(String context,
                                        GeminiService.LeadClassification classification) {
        String emoji = switch (classification) {
            case HOT  -> "🔥";
            case WARM -> "🌡️";
            case COLD -> "💙";
            case UNKNOWN -> "🙂";
        };

        String nextStep = switch (classification) {
            case HOT  -> "I'd love to get started on a proposal for you. Can we schedule a quick 30-minute call?";
            case WARM -> "I'll follow up with you soon. Feel free to reach out anytime with questions.";
            case COLD -> "No pressure at all. I'll send you our portfolio in case it's useful down the line.";
            case UNKNOWN -> "Thanks for your time. I'll follow up soon, and feel free to reach out anytime with questions.";
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
