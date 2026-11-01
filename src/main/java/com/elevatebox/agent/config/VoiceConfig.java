package com.elevatebox.agent.config;

import com.elevatebox.agent.service.BolnaService;
import com.elevatebox.agent.service.OutboundCaller;
import com.elevatebox.agent.service.VapiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Picks the voice platform that places calls: {@code VOICE_PROVIDER=vapi} (the default) or
 * {@code bolna}. Both platforms report back to their own endpoints, so switching is only this
 * setting plus the platform's dashboard configuration.
 */
@Slf4j
@Configuration
public class VoiceConfig {

    @Bean
    public OutboundCaller outboundCaller(@Value("${voice.provider:vapi}") String provider,
                                         VapiService vapi, BolnaService bolna) {
        String chosen = provider.trim().toLowerCase(Locale.ROOT);
        switch (chosen) {
            case "bolna" -> {
                log.info("Calls are placed through Bolna");
                return bolna::initiateCall;
            }
            case "vapi" -> {
                log.info("Calls are placed through Vapi");
                return vapi::initiateCall;
            }
            default -> throw new IllegalStateException("voice.provider must be vapi or bolna, not " + provider);
        }
    }
}
