package com.elevatebox.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends the personalised WhatsApp follow-up right after a voice call ends.
 *
 * <p>The recap of what the lead said comes from the single Gemini analysis done by
 * {@link LeadClassifierService}. This class wraps it in a message whose tone and call-to-action
 * depend on the lead's classification (HOT, WARM, COLD or UNKNOWN), signs it with the agent's
 * contact details, and delivers it through {@link WhatsAppService}.
 *
 * <p>It deliberately does not call Gemini itself: if the recap is missing because Gemini was
 * down, a generic thank-you is used, so the lead still gets a message.
 *
 * <p>The sender's name, phone number, resume link and architecture link are configurable
 * through the {@code agent.*} properties. The phone number is required and has no default,
 * so no personal number is baked into the code; the other values fall back to public links.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowUpService {

    static final String FALLBACK_RECAP =
            "Thanks for taking my call just now. It was good to hear about your business.";

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
     *   <li>Context of the call, taken from the real conversation</li>
     *   <li>Human framing, written like a person rather than a log</li>
     *   <li>The sender's mobile number</li>
     *   <li>A link to how the system was built, plus the sender's resume</li>
     * </ol>
     *
     * @param recap          short recap of the call; blank means "use a generic thank-you"
     * @param classification lead temperature, which decides the emoji and the next-step text
     */
    public void sendPostCallFollowUp(String recap, GeminiService.LeadClassification classification) {
        sendPostCallFollowUp(recap, classification, null);
    }

    /**
     * Same as {@link #sendPostCallFollowUp(String, GeminiService.LeadClassification)}, sent to a
     * specific number.
     *
     * @param recipient the person who was on the call, or null for the configured target number
     */
    public void sendPostCallFollowUp(String recap, GeminiService.LeadClassification classification,
                                     String recipient) {
        log.info("Building post-call follow-up WhatsApp...");

        String context = (recap == null || recap.isBlank()) ? FALLBACK_RECAP : recap.trim();
        String message = buildFollowUpMessage(context, classification);

        whatsAppService.sendPostCallMessage(recipient, message);
        log.info("Post-call follow-up sent — classification: {}", classification);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Assembles the final WhatsApp text from the recap, a classification-based next step,
     * and the sender's details.
     *
     * <p>Classification mapping: HOT proposes a 30-minute call, WARM promises a follow-up,
     * COLD keeps it low-pressure and offers a portfolio, UNKNOWN is a neutral thank-you.
     */
    private String buildFollowUpMessage(String context, GeminiService.LeadClassification classification) {
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
