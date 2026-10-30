package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.OptInService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WhatsAppWebhookControllerTest {

    private static final String SECRET = "app-secret";
    private static final String BODY = """
            {"entry":[{"changes":[{"value":{"messages":[{"from":"919876543210","id":"wamid.A","text":{"body":"call me"}}]}}]}]}""";

    private ThreadPoolTaskExecutor executor;
    private WhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        executor = mock(ThreadPoolTaskExecutor.class);
        controller = new WhatsAppWebhookController(mock(OptInService.class), executor, "verify-me", SECRET);
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(UTF_8)));
    }

    @Test
    void verificationEchoesTheChallengeForTheRightToken() {
        var ok = controller.verify("subscribe", "verify-me", "12345");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("12345", ok.getBody());

        assertEquals(403, controller.verify("subscribe", "wrong", "12345").getStatusCode().value());
        assertEquals(403, controller.verify("unsubscribe", "verify-me", "12345").getStatusCode().value());
    }

    @Test
    void signedMessageIsQueued() throws Exception {
        var response = controller.receive(sign(BODY, SECRET), BODY.getBytes(UTF_8));

        assertEquals(200, response.getStatusCode().value());
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void wrongOrMissingSignatureIsRejected() throws Exception {
        assertEquals(401, controller.receive(sign(BODY, "other"), BODY.getBytes(UTF_8)).getStatusCode().value());
        assertEquals(401, controller.receive(null, BODY.getBytes(UTF_8)).getStatusCode().value());
        assertEquals(401, controller.receive("sha256=zz", BODY.getBytes(UTF_8)).getStatusCode().value());
        verifyNoInteractions(executor);
    }

    @Test
    void nothingIsAcceptedWithoutConfiguration() throws Exception {
        WhatsAppWebhookController off = new WhatsAppWebhookController(mock(OptInService.class), executor, "", "");

        assertEquals(403, off.verify("subscribe", "", "1").getStatusCode().value());
        assertEquals(401, off.receive(sign(BODY, SECRET), BODY.getBytes(UTF_8)).getStatusCode().value());
    }
}
