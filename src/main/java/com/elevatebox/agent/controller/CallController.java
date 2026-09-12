package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
public class CallController {

    private final VapiService            vapiService;
    private final GeminiService          geminiService;
    private final WhatsAppService        whatsAppService;
    private final LeadClassifierService  leadClassifierService;

    // ─── 1. Trigger outbound call ─────────────────────────────────────────────

    /**
     * POST /api/call/initiate
     * Kicks off the entire flow:
     *   1. (Optional) send pre-call WhatsApp
     *   2. Initiate Vapi outbound call
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiateCall(
            @RequestParam(defaultValue = "false") boolean sendPreCallMessage) {

        log.info("Received /initiate request | preCall={}", sendPreCallMessage);

        String preSid = null;
        if (sendPreCallMessage) {
            preSid = whatsAppService.sendPreCallMessage();
        }

        String vapiResponse = vapiService.initiateCall();

        return ResponseEntity.ok(Map.of(
                "status",          "CALL_INITIATED",
                "vapiResponse",    vapiResponse,
                "preCallMsgSid",   preSid != null ? preSid : "not_sent"
        ));
    }

    // ─── 2. Vapi webhook ─────────────────────────────────────────────────────

    /**
     * POST /api/call/webhook
     * Vapi sends call events here. Handle end-of-call transcript for post-processing.
     *
     * Vapi event types you care about:
     *   - call.started
     *   - call.ended  ← this one carries the transcript
     *   - transcript  ← real-time transcript chunks (for mid-call triggers)
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> vapiWebhook(@RequestBody String payload) {
        log.info("Vapi webhook received: {}", payload.substring(0, Math.min(200, payload.length())));

        try {
            com.google.gson.JsonObject event = com.google.gson.JsonParser
                    .parseString(payload).getAsJsonObject();

            String type = event.has("type")
                    ? event.get("type").getAsString()
                    : event.has("message") ? event.getAsJsonObject("message").get("type").getAsString() : "";

            switch (type) {
                case "call.ended", "end-of-call-report" -> handleCallEnded(event);
                case "transcript"                        -> handleTranscript(event);
                default                                  -> log.debug("Unhandled Vapi event: {}", type);
            }

        } catch (Exception e) {
            log.error("Error processing Vapi webhook: {}", e.getMessage(), e);
        }

        // Vapi expects 200 quickly — always ack
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
        return ResponseEntity.ok(result);
    }

    // ─── 4. Mid-call WhatsApp trigger ─────────────────────────────────────────

    /**
     * POST /api/call/mid-call
     * Body: { "transcript": "..." }
     * Send a contextual WhatsApp while the call is still live.
     */
    @PostMapping("/mid-call")
    public ResponseEntity<Map<String, String>> midCallMessage(
            @RequestBody Map<String, String> body) {

        String transcript = body.get("transcript");
        if (transcript == null || transcript.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String sid = leadClassifierService.processMidCall(transcript);
        return ResponseEntity.ok(Map.of("status", "SENT", "messageSid", sid));
    }

    // ─── 5. Quick classify ────────────────────────────────────────────────────

    /**
     * POST /api/call/classify
     * Body: { "transcript": "..." }
     * Returns HOT/WARM/COLD with no side effects.
     */
    @PostMapping("/classify")
    public ResponseEntity<Map<String, String>> classifyLead(
            @RequestBody Map<String, String> body) {

        String transcript = body.get("transcript");
        if (transcript == null || transcript.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        GeminiService.LeadClassification result = leadClassifierService.quickClassify(transcript);
        return ResponseEntity.ok(Map.of("classification", result.name()));
    }

    // ─── 6. Health check ─────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "ElevateBox AI Agent"));
    }

    // ─── Private event handlers ───────────────────────────────────────────────

    private void handleCallEnded(com.google.gson.JsonObject event) {
        log.info("Call ended — extracting transcript for post-processing");
        try {
            // Vapi's end-of-call-report has transcript under different paths
            String transcript = extractTranscript(event);
            if (transcript != null && !transcript.isBlank()) {
                leadClassifierService.processPostCall(transcript);
            } else {
                log.warn("Call ended but transcript was empty");
            }
        } catch (Exception e) {
            log.error("Error in handleCallEnded: {}", e.getMessage(), e);
        }
    }

    private void handleTranscript(com.google.gson.JsonObject event) {
        // Real-time transcript — you could use this to trigger mid-call messages
        // based on keywords e.g. if prospect says "send me more info"
        try {
            String text = "";
            if (event.has("transcript")) {
                text = event.get("transcript").getAsString();
            } else if (event.has("message")) {
                var msg = event.getAsJsonObject("message");
                if (msg.has("transcript")) text = msg.get("transcript").getAsString();
            }

            // Keyword trigger — e.g. send brochure if prospect asks
            if (text.toLowerCase().contains("send me") || text.toLowerCase().contains("more info")) {
                log.info("Mid-call keyword detected — triggering WhatsApp");
                leadClassifierService.processMidCall(text);
            }
        } catch (Exception e) {
            log.error("Error in handleTranscript: {}", e.getMessage(), e);
        }
    }

    private String extractTranscript(com.google.gson.JsonObject event) {
        // Try common Vapi response paths
        if (event.has("transcript"))     return event.get("transcript").getAsString();
        if (event.has("call")) {
            var call = event.getAsJsonObject("call");
            if (call.has("transcript")) return call.get("transcript").getAsString();
        }
        if (event.has("message")) {
            var msg = event.getAsJsonObject("message");
            if (msg.has("transcript")) return msg.get("transcript").getAsString();
            if (msg.has("call")) {
                var call = msg.getAsJsonObject("call");
                if (call.has("transcript")) return call.get("transcript").getAsString();
            }
        }
        return null;
    }
}
