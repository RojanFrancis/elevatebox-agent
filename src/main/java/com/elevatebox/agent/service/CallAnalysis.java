package com.elevatebox.agent.service;

/**
 * Everything the post-call pipeline needs from one Gemini call: the lead's classification and
 * reason, a summary, a WhatsApp-ready recap, and any callback request.
 *
 * <p>Getting all of this from one request instead of four keeps the pipeline inside API rate
 * limits, and means one model outage costs one failed step instead of four.
 *
 * @param label             HOT, WARM, COLD, or UNKNOWN when it could not be judged; never null
 * @param reason            one-sentence reason for the label; never null
 * @param summary           3-4 sentence summary of the call; never null
 * @param followUpRecap     2-3 sentence recap for the follow-up WhatsApp; empty if unavailable
 * @param callbackRequested true only if the lead asked to be called back and said when
 * @param callbackWhen      the lead's own words for when (for example "tomorrow morning"), or empty
 */
public record CallAnalysis(GeminiService.LeadClassification label,
                           String reason,
                           String summary,
                           String followUpRecap,
                           boolean callbackRequested,
                           String callbackWhen) {

    public CallAnalysis {
        label = label == null ? GeminiService.LeadClassification.UNKNOWN : label;
        reason = reason == null ? "" : reason;
        summary = summary == null ? "" : summary;
        followUpRecap = followUpRecap == null ? "" : followUpRecap;
        callbackWhen = callbackWhen == null ? "" : callbackWhen.trim();
        callbackRequested = callbackRequested && !callbackWhen.isBlank();
    }

    /** The result when Gemini could not be reached or its reply was unusable. */
    public static CallAnalysis unavailable() {
        return new CallAnalysis(GeminiService.LeadClassification.UNKNOWN, "analysis unavailable", "", "", false, "");
    }
}
