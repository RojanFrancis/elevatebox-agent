package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.CallAnalysis;
import com.elevatebox.agent.service.DemoLimiter;
import com.elevatebox.agent.service.GeminiService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public demo: paste a transcript, get the analysis back.
 *
 * <p>Safe to leave open on the internet because it can only analyse. It has no way to send a
 * WhatsApp message, call anyone, or write to the database, since none of those services are
 * injected here. The cost it can cause (Gemini quota) is capped by {@link DemoLimiter} and by
 * the transcript length limit. This is the only {@code /api} route that does not need the API key.
 */
@Slf4j
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final GeminiService geminiService;
    private final DemoLimiter limiter;
    private final int maxChars;

    @Autowired
    public DemoController(GeminiService geminiService,
                          DemoLimiter limiter,
                          @Value("${demo.max-chars:4000}") int maxChars) {
        this.geminiService = geminiService;
        this.limiter = limiter;
        this.maxChars = maxChars;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> body,
                                                       HttpServletRequest request) {
        String transcript = body == null ? null : body.get("transcript");
        if (transcript == null || transcript.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Paste a transcript first.");
        }
        if (transcript.length() > maxChars) {
            return error(HttpStatus.BAD_REQUEST,
                    "That transcript is too long. The demo accepts up to " + maxChars + " characters.");
        }

        switch (limiter.tryAcquire(callerKey(request))) {
            case ALLOWED -> { }
            case RATE_LIMITED -> {
                return error(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Wait a minute and try again.");
            }
            case DAILY_LIMIT -> {
                return error(HttpStatus.TOO_MANY_REQUESTS, "You have used today's demo allowance. Come back tomorrow.");
            }
            case GLOBAL_LIMIT -> {
                return error(HttpStatus.SERVICE_UNAVAILABLE, "The demo has reached its daily limit. Try again tomorrow.");
            }
        }

        try {
            CallAnalysis analysis = geminiService.analyzeCall(transcript);
            return ResponseEntity.ok(Map.of(
                    "label", analysis.label().name(),
                    "reason", analysis.reason(),
                    "summary", analysis.summary(),
                    "followUpRecap", analysis.followUpRecap(),
                    "callbackRequested", analysis.callbackRequested(),
                    "callbackWhen", analysis.callbackWhen(),
                    "note", "Analysis only. No WhatsApp message was sent."));
        } catch (RuntimeException e) {
            log.warn("Demo analysis failed: {}", e.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI model is busy or out of quota right now. Try again in a few minutes.");
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    /** The caller's address; behind a proxy such as Render the first forwarded address is the client. */
    private static String callerKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
