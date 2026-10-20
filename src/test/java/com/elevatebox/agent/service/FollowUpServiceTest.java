package com.elevatebox.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.elevatebox.agent.service.GeminiService.LeadClassification.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FollowUpServiceTest {

    private WhatsAppService whatsApp;
    private FollowUpService service;

    @BeforeEach
    void setUp() {
        whatsApp = mock(WhatsAppService.class);
        service = new FollowUpService(whatsApp);
        ReflectionTestUtils.setField(service, "agentName", "Test Agent");
        ReflectionTestUtils.setField(service, "agentPhone", "+10000000000");
        ReflectionTestUtils.setField(service, "resumeLink", "https://example.com/resume");
        ReflectionTestUtils.setField(service, "buildImageUrl", "https://example.com/build");
    }

    private String sent() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendPostCallMessage(message.capture());
        return message.getValue();
    }

    @Test
    void messageCarriesTheRecapTheNextStepAndTheContactDetails() {
        service.sendPostCallFollowUp("You run a salon and want online bookings.", HOT);

        String message = sent();
        assertTrue(message.contains("You run a salon and want online bookings."));
        assertTrue(message.contains("30-minute call"));
        assertTrue(message.contains("Test Agent"));
        assertTrue(message.contains("+10000000000"));
        assertTrue(message.contains("https://example.com/resume"));
        assertTrue(message.contains("https://example.com/build"));
    }

    @Test
    void blankRecapFallsBackToAGenericThankYou() {
        service.sendPostCallFollowUp("   ", UNKNOWN);

        String message = sent();
        assertTrue(message.contains(FollowUpService.FALLBACK_RECAP));
        assertTrue(message.contains("Thanks for your time"));
    }

    @Test
    void nullRecapIsTreatedLikeBlank() {
        service.sendPostCallFollowUp(null, COLD);

        assertTrue(sent().contains(FollowUpService.FALLBACK_RECAP));
    }
}
