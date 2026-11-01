package com.elevatebox.agent.service;

/**
 * Places one outbound call. Implemented by Vapi and by Bolna; {@code voice.provider} picks which
 * one the opt-in flow uses (see {@code VoiceConfig}).
 */
@FunctionalInterface
public interface OutboundCaller {

    /**
     * @param number the number to call, in E.164
     * @param name   the customer's name for the agent, or null
     * @return the provider's ID for the call
     * @throws RuntimeException if the provider refuses the call
     */
    String initiateCall(String number, String name);
}
