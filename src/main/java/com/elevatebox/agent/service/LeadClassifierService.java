package com.elevatebox.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orchestrates what happens after (and during) a call.
 *
 * <p>The design rule: every step is independent. A failed WhatsApp send must not stop the
 * summary, and a Gemini outage must not stop the follow-up. Each step runs inside its own
 * try/catch; failures are logged, collected in {@link LeadResult#failedSteps()}, and the
 * pipeline carries on with a safe fallback.
 */
@Slf4j
@Service
public class LeadClassifierService {

    private static final String LINK_PLACEHOLDER = "[LINK]";

    private final GeminiService   geminiService;
    private final WhatsAppService whatsAppService;
    private final FollowUpService followUpService;
    private final CallbackService callbackService;
    private final String          brochureUrl;

    public LeadClassifierService(GeminiService geminiService,
                                 WhatsAppService whatsAppService,
                                 FollowUpService followUpService,
                                 CallbackService callbackService,
                                 @Value("${agent.brochure-url:https://github.com/RojanFrancis/elevatebox-agent}")
                                 String brochureUrl) {
        this.geminiService   = geminiService;
        this.whatsAppService = whatsAppService;
        this.followUpService = followUpService;
        this.callbackService = callbackService;
        this.brochureUrl     = brochureUrl;
    }

    /**
     * Runs the full post-call pipeline on ONE Gemini call. A single analysis gives the
     * classification, summary, WhatsApp recap and any callback request together, instead of four
     * separate requests. That keeps the pipeline inside API rate limits, and one outage costs one
     * failed step, not four.
     *
     * <p>The follow-up WhatsApp does not depend on Gemini being up: if the analysis fails, the lead
     * is UNKNOWN and still gets a neutral thank-you. Every step is independent; failures are
     * logged and listed in {@link LeadResult#failedSteps()}.
     *
     * @param transcript the full call transcript
     * @return the outcome, including the names of any steps that failed
     */
    public LeadResult processPostCall(String transcript) {
        return processPostCall(transcript, null);
    }

    /**
     * Same as {@link #processPostCall(String)}, with the WhatsApps sent to the person who was on
     * the call.
     *
     * @param recipient the caller's number, or null for the configured target number
     */
    public LeadResult processPostCall(String transcript, String recipient) {
        log.info("Starting post-call pipeline...");
        List<String> failures = new ArrayList<>();

        // Step 1: one Gemini call for everything (UNKNOWN if the model is unavailable or unclear)
        CallAnalysis analysis = Objects.requireNonNullElse(
                stepValue("analysis", failures, () -> geminiService.analyzeCall(transcript),
                        CallAnalysis.unavailable()),
                CallAnalysis.unavailable());
        GeminiService.LeadClassification label = analysis.label();
        log.info("Lead classified as: {}", label);

        // Step 2: personalised follow-up WhatsApp (a generic thank-you if the recap is missing)
        stepRun("follow-up", failures,
                () -> followUpService.sendPostCallFollowUp(analysis.followUpRecap(), label, recipient));

        // Step 3: confirm a callback request, only worth doing for interested leads
        boolean callbackRequested = false;
        String callbackTime = null;
        if (analysis.callbackRequested()
                && (label == GeminiService.LeadClassification.HOT || label == GeminiService.LeadClassification.WARM)) {
            callbackRequested = true;
            callbackTime = analysis.callbackWhen();
            String when = analysis.callbackWhen();
            log.info("Callback requested: {}", when);
            stepRun("callback-confirmation", failures, () -> callbackService.sendCallbackConfirmation(when, recipient));
        }

        LeadResult result = new LeadResult(label, analysis.reason(), analysis.summary(),
                callbackRequested, callbackTime, List.copyOf(failures));
        log.info("Post-call pipeline complete: classification={} callbackRequested={} failedSteps={}",
                label, callbackRequested, failures);
        return result;
    }

    /**
     * Mid-call pipeline: classifies the conversation so far and sends a short WhatsApp only if
     * the lead is HOT. The caller is responsible for "at most once per call" (see
     * {@link CallStateStore}).
     *
     * <p>The model's {@code [LINK]} placeholder is replaced with the configured brochure URL;
     * if the model left the placeholder out, the URL is appended, so a lead never receives a
     * literal "[LINK]" or a message with no link.
     *
     * @param partialTranscript the transcript accumulated so far
     * @return the WhatsApp message ID if a message was sent, empty if the lead was not HOT
     * @throws RuntimeException if Gemini or WhatsApp fails; the caller decides whether to retry
     */
    public Optional<String> processMidCall(String partialTranscript) {
        log.info("Processing mid-call WhatsApp trigger...");

        GeminiService.ClassificationResult classification = geminiService.classifyLead(partialTranscript);
        if (classification.label() != GeminiService.LeadClassification.HOT) {
            log.info("Mid-call: intent not HOT ({}), skipping WhatsApp", classification.label());
            return Optional.empty();
        }

        String message = withLink(geminiService.generateMidCallMessage(partialTranscript));
        String sid = whatsAppService.sendMidCallMessage(message);
        log.info("Mid-call WhatsApp sent — SID: {}", sid);
        return Optional.of(sid);
    }

    /** Quick classify only, with no side effects. */
    public GeminiService.ClassificationResult quickClassify(String transcript) {
        return geminiService.classifyLead(transcript);
    }

    private String withLink(String modelMessage) {
        if (modelMessage == null || modelMessage.isBlank()) {
            return "Thanks for your interest in ElevateBox! Here is some of our work: " + brochureUrl;
        }
        String message = modelMessage.replace(LINK_PLACEHOLDER, brochureUrl);
        return message.contains(brochureUrl) ? message : message + "\n" + brochureUrl;
    }

    private <T> T stepValue(String name, List<String> failures, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Exception e) {
            log.error("Pipeline step '{}' failed: {}", name, e.getMessage());
            failures.add(name);
            return fallback;
        }
    }

    private void stepRun(String name, List<String> failures, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("Pipeline step '{}' failed: {}", name, e.getMessage());
            failures.add(name);
        }
    }

    /**
     * Outcome of the post-call pipeline.
     *
     * @param classification    HOT, WARM, COLD, or UNKNOWN when it could not be judged
     * @param reason            the model's one-sentence reason (may be empty)
     * @param summary           the call summary, empty if that step failed
     * @param callbackRequested true if the lead asked to be called back at a specific time
     * @param callbackTime      the requested time in plain language, or null
     * @param failedSteps       names of steps that failed, empty when everything worked
     */
    public record LeadResult(
            GeminiService.LeadClassification classification,
            String reason,
            String summary,
            boolean callbackRequested,
            String callbackTime,
            List<String> failedSteps
    ) {}
}
