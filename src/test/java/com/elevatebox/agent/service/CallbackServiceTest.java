package com.elevatebox.agent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CallbackServiceTest {

    @Test
    void confirmationMentionsTheTimeTheLeadAskedFor() {
        WhatsAppService whatsApp = mock(WhatsAppService.class);
        new CallbackService(whatsApp).sendCallbackConfirmation("tomorrow morning");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendPostCallMessage(message.capture());
        assertTrue(message.getValue().contains("tomorrow morning"));
    }
}
