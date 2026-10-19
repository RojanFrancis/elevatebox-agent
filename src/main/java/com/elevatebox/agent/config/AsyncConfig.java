package com.elevatebox.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * A small bounded thread pool for webhook work, so the webhook can answer Vapi immediately
 * while classification and WhatsApp sends run in the background.
 *
 * <p>The queue is bounded on purpose: if it fills up the webhook answers 503 and Vapi retries,
 * instead of the process piling up unbounded work. On shutdown, queued work gets 30 seconds
 * to finish.
 */
@Configuration
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("webhook-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
