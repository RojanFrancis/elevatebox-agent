package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.OptInService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Receives WhatsApp messages sent to the business number (Meta WhatsApp Cloud API webhook).
 *
 * <p>This is how a call starts: a visitor taps "Get a call" on the website, WhatsApp opens with
 * "Hi, please call me", they send it, and {@link OptInService} decides whether to call them.
 *
 * <p>Meta does not send the API key, so {@code ApiKeyFilter} skips this path and it is
 * authenticated here instead:
 * <ul>
 *   <li>{@code GET}: Meta's one-time subscription check, which must echo {@code hub.challenge}
 *       when {@code hub.verify_token} matches {@code meta.whatsapp.verify-token}.</li>
 *   <li>{@code POST}: every delivery is signed with the app secret in
 *       {@code X-Hub-Signature-256} ({@code sha256=} + hex HMAC of the raw body). Unsigned or
 *       wrongly signed bodies get 401.</li>
 * </ul>
 * Both fail closed: if the token or secret is not configured, nothing is accepted.
 */
@Slf4j
@RestController
@RequestMapping("/api/whatsapp/webhook")
public class WhatsAppWebhookController {

    private final OptInService optIn;
    private final ThreadPoolTaskExecutor webhookExecutor;
    private final String verifyToken;
    private final byte[] appSecret;

    public WhatsAppWebhookController(OptInService optIn,
                                     ThreadPoolTaskExecutor webhookExecutor,
                                     @Value("${meta.whatsapp.verify-token:}") String verifyToken,
                                     @Value("${meta.whatsapp.app-secret:}") String appSecret) {
        this.optIn = optIn;
        this.webhookExecutor = webhookExecutor;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret.getBytes(UTF_8);
        if (verifyToken.isBlank() || appSecret.isBlank()) {
            log.warn("WhatsApp opt-in is off: set WHATSAPP_VERIFY_TOKEN and META_APP_SECRET to receive messages");
        }
    }

    @GetMapping
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && !verifyToken.isBlank() && challenge != null
                && MessageDigest.isEqual(verifyToken.getBytes(UTF_8), String.valueOf(token).getBytes(UTF_8))) {
            log.info("WhatsApp webhook subscription verified");
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Answers straight away; each message is handled on the background pool. Meta retries a
     * delivery it did not get a 200 for, and {@link OptInService} ignores repeated message IDs.
     */
    @PostMapping
    public ResponseEntity<String> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                          @RequestBody byte[] body) {
        // The signature covers the exact bytes Meta sent, so it is checked before any decoding
        if (!validSignature(body, signature)) {
            log.warn("Rejecting WhatsApp webhook with a missing or wrong signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<WhatsAppInbound.Message> messages;
        try {
            messages = WhatsAppInbound.parse(new String(body, UTF_8));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body("BAD_PAYLOAD");
        }

        for (WhatsAppInbound.Message message : messages) {
            try {
                webhookExecutor.execute(() -> optIn.handle(message));
            } catch (RejectedExecutionException e) {
                log.error("Webhook queue full, asking Meta to retry the delivery");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("BUSY");
            }
        }
        return ResponseEntity.ok("OK");
    }

    boolean validSignature(byte[] body, String header) {
        if (appSecret.length == 0 || header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] provided = HexFormat.of().parseHex(header.substring("sha256=".length()));
            return MessageDigest.isEqual(expected, provided);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }
}
