package com.elevatebox.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Authenticates every {@code /api/**} request except the health check and the public demo
 * analysis endpoint (which can only analyse text; see {@code DemoController}).
 *
 * <p>Two callers, two secrets, so a leak of one does not expose the other:
 * <ul>
 *   <li>{@code /api/call/webhook} and the tool endpoints under {@code /api/call/tools/} are
 *       called by Vapi and must carry {@code vapi.webhook-secret}, either as
 *       {@code X-Vapi-Secret} or as an {@code Authorization: Bearer} token (both are formats
 *       Vapi can send).</li>
 *   <li>Everything else is called by the owner and must carry {@code app.api-key}
 *       in the {@code X-API-Key} header.</li>
 * </ul>
 *
 * <p>Fails closed: the application refuses to start if either secret is missing
 * or shorter than {@value #MIN_SECRET_LENGTH} characters.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    static final int MIN_SECRET_LENGTH = 24;
    private static final String WEBHOOK_PATH = "/api/call/webhook";
    private static final String TOOLS_PREFIX = "/api/call/tools/";
    private static final String HEALTH_PATH  = "/api/call/health";
    private static final String DEMO_PATH    = "/api/demo/analyze";

    private final byte[] apiKey;
    private final byte[] webhookSecret;

    public ApiKeyFilter(@Value("${app.api-key}") String apiKey,
                        @Value("${vapi.webhook-secret}") String webhookSecret) {
        if (apiKey.length() < MIN_SECRET_LENGTH || webhookSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "app.api-key and vapi.webhook-secret must each be at least "
                            + MIN_SECRET_LENGTH + " characters (try: openssl rand -hex 32)");
        }
        this.apiKey = apiKey.getBytes(UTF_8);
        this.webhookSecret = webhookSecret.getBytes(UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !path.startsWith("/api/") || path.equals(HEALTH_PATH) || path.equals(DEMO_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        boolean authenticated;
        String path = req.getRequestURI();
        if (path.equals(WEBHOOK_PATH) || path.startsWith(TOOLS_PREFIX)) {
            authenticated = matches(webhookSecret, req.getHeader("X-Vapi-Secret"))
                    || matches(webhookSecret, bearerToken(req.getHeader("Authorization")));
        } else {
            authenticated = matches(apiKey, req.getHeader("X-API-Key"));
        }

        if (!authenticated) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(req, res);
    }

    /** Constant-time comparison so response timing does not reveal how much of a key matched. */
    private boolean matches(byte[] expected, String provided) {
        return provided != null && MessageDigest.isEqual(expected, provided.getBytes(UTF_8));
    }

    private String bearerToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
