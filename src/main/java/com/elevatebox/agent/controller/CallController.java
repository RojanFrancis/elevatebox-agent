package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.CallRecordService;
import com.elevatebox.agent.service.CallStateStore;
import com.elevatebox.agent.service.GeminiService;
import com.elevatebox.agent.service.LeadClassifierService;
import com.elevatebox.agent.service.VapiService;
import com.elevatebox.agent.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
public class CallController {

    /** Phrases that make a live utterance worth checking for a mid-call WhatsApp. */
    private static final Pattern MID_CALL_TRIGGER = Pattern.compile(
            "send me|more info|whatsapp|pric(e|ing)|brochure|portfolio", Pattern.CASE_INSENSITIVE);

    private final VapiService            vapiService;
    private final WhatsAppService        whatsAppService;
    private final LeadClassifierService  leadClassifierService;
    private final CallStateStore         callState;
    private final ThreadPoolTaskExecutor webhookExecutor;
    private final CallRecordService      callRecords;

    // ─── 1. Trigger outbound call ─────────────────────────────────────────────

    /**
     * POST /api/call/initiate
     * Kicks off the entire flow:
     *   1. (Optional) send pre-call WhatsApp
     *   2. Initiate Vapi outbound call
     * Returns only the Vapi call ID, not Vapi's full response.
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiateCall(
            @RequestParam(defaultValue = "false") boolean sendPreCallMessage) {

        log.info("Received /initiate request | preCall={}", sendPreCallMessage);

        String preSid = null;
        if (sendPreCallMessage) {
            preSid = whatsAppService.sendPreCallMessage();
        }

        String callId = vapiService.initiateCall();

        return ResponseEntity.ok(Map.of(
                "status",          "CALL_INITIATED",
                "callId",          callId,
                "preCallMsgSid",   preSid != null ? preSid : "not_sent"
        ));
    }

    // ─── 2. Vapi webhook ─────────────────────────────────────────────────────

    /**
     * POST /api/call/webhook
     * Vapi sends call events here. The webhook only parses, de-duplicates and queues the work,
     * then answers straight away; classification and WhatsApp sends run on a background pool.
     *
     * <ul>
     *   <li>{@code end-of-call-report}: runs the post-call pipeline once per call. A repeated
     *       delivery for the same call ID is acknowledged and ignored.</li>
     *   <li>final {@code transcript} events: build up the live transcript; a user utterance that
     *       matches {@link #MID_CALL_TRIGGER} triggers the mid-call check, at most once per call
     *       and never for the assistant's own words or partial updates.</li>
     * </ul>
     *
     * Answers 400 for a body that is not a JSON object, and 503 if the work queue is full so
     * that Vapi retries.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> vapiWebhook(@RequestBody String payload) {
        log.info("Vapi webhook received ({} bytes)", payload.length());

        VapiEvent event;
        try {
            event = VapiEvent.parse(payload);
        } catch (RuntimeException e) {
            log.warn("Rejecting webhook body that is not a JSON object");
            return ResponseEntity.badRequest().body("BAD_PAYLOAD");
        }

        log.info("Vapi event: type={} callId={}", event.type(), event.callId());
        try {
            if (event.isEndOfCall()) {
                return handleCallEnded(event);
            }
            if (event.isFinalTranscript()) {
                handleTranscript(event);
            } else {
                log.debug("Ignoring Vapi event: {}", event.type());
            }
        } catch (RuntimeException e) {
            log.error("Error handling Vapi webhook: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok("OK");
    }

    // ─── 3. Manual post-call processing ──────────────────────────────────────

    /**
     * POST /api/call/process
     * Body: { "transcript": "..." }
     * Manually trigger post-call classification + WhatsApp follow-up.
     * Useful for testing without a live call.
     */
    @PostMapping("/process")
    public ResponseEntity<LeadClassifierService.LeadResult> processCall(
            @RequestBody Map<String, String> body) {

        String transcript = body.get("transcript");
        if (transcript == null || transcript.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Manual post-call processing triggered");
        LeadClassifierService.LeadResult result = leadClassifierService.processPostCall(transcript);
        callRecords.save(null, "manual", result);
        return ResponseEntity.ok(result);
    }

    // ─── 4. Mid-call WhatsApp trigger ─────────────────────────────────────────

    /**
     * POST /api/call/mid-call
     * Body: { "transcript": "..." }
     * Send a contextual WhatsApp while the call is still live, if the lead is HOT.
     */
    @PostMapping("/mid-call")
    public ResponseEntity<Map<String, String>> midCallMessage(
            @RequestBody Map<String, String> body) {

        String transcript = body.get("transcript");
        if (transcript == null || transcript.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Optional<String> sid = leadClassifierService.processMidCall(transcript);
        return ResponseEntity.ok(Map.of(
                "status",     sid.isPresent() ? "SENT" : "SKIPPED_NOT_HOT",
                "messageSid", sid.orElse("none")));
    }

    // ─── 5. Quick classify ────────────────────────────────────────────────────

    /**
     * POST /api/call/classify
     * Body: { "transcript": "..." }
     * Returns the classification (HOT/WARM/COLD/UNKNOWN) and the reason, with no side effects.
     */
    @PostMapping("/classify")
    public ResponseEntity<Map<String, String>> classifyLead(
            @RequestBody Map<String, String> body) {

        String transcript = body.get("transcript");
        if (transcript == null || transcript.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        GeminiService.ClassificationResult result = leadClassifierService.quickClassify(transcript);
        return ResponseEntity.ok(Map.of(
                "classification", result.label().name(),
                "reason",         result.reason()));
    }

    // ─── 6. Health check ─────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "ElevateBox AI Agent"));
    }

    // ─── Private event handlers ───────────────────────────────────────────────

    private ResponseEntity<String> handleCallEnded(VapiEvent event) {
        String transcript = event.endOfCallTranscript();
        String callId = event.callId();

        if (transcript == null || transcript.isBlank()) {
            log.warn("Call ended but transcript was empty (callId={})", callId);
            return ResponseEntity.ok("OK");
        }
        if (callId != null && !callState.markProcessed(callId)) {
            log.info("Duplicate end-of-call report for call {}, ignoring", callId);
            return ResponseEntity.ok("DUPLICATE");
        }

        if (callId != null && callRecords.exists(callId)) {
            log.info("Call {} is already stored, ignoring the repeated report", callId);
            return ResponseEntity.ok("DUPLICATE");
        }

        try {
            webhookExecutor.execute(() -> runPostCall(callId, transcript));
        } catch (RejectedExecutionException e) {
            log.error("Webhook queue full, asking Vapi to retry (callId={})", callId);
            if (callId != null) {
                callState.clearProcessed(callId);
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("BUSY");
        }
        return ResponseEntity.ok("OK");
    }

    private void runPostCall(String callId, String transcript) {
        try {
            LeadClassifierService.LeadResult result = leadClassifierService.processPostCall(transcript);
            callRecords.save(callId, "vapi", result);
        } catch (Exception e) {
            log.error("Post-call pipeline crashed (callId={}): {}", callId, e.getMessage(), e);
        }
    }

    private void handleTranscript(VapiEvent event) {
        String callId = event.callId();
        String text = event.transcript();
        if (callId == null || text == null || text.isBlank()) {
            return;
        }

        callState.appendLine(callId, event.role(), text);

        // Only the lead's own words can trigger it, and only once per call
        if (!"user".equals(event.role()) || !MID_CALL_TRIGGER.matcher(text).find()) {
            return;
        }
        if (!callState.tryStartMidCall(callId)) {
            return;
        }

        String transcriptSoFar = callState.transcript(callId);
        try {
            webhookExecutor.execute(() -> runMidCall(callId, transcriptSoFar));
        } catch (RejectedExecutionException e) {
            callState.finishMidCall(callId, false);
            log.warn("Webhook queue full, skipping mid-call check (callId={})", callId);
        }
    }

    private void runMidCall(String callId, String transcriptSoFar) {
        boolean sent = false;
        try {
            sent = leadClassifierService.processMidCall(transcriptSoFar).isPresent();
        } catch (Exception e) {
            log.error("Mid-call WhatsApp failed (callId={}): {}", callId, e.getMessage());
        } finally {
            callState.finishMidCall(callId, sent);
        }
    }
}
