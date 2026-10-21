package com.elevatebox.agent.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Limits the public demo endpoint, which spends real Gemini quota for anyone who finds the URL.
 *
 * <p>Three limits, checked in this order: a global daily cap (protects the whole quota no matter
 * how many callers), then per-caller limits per minute and per day. Only allowed requests count.
 * State is in memory, so it resets on restart; fine for one instance. The caller key is an IP
 * address, which can be spoofed through a forwarded header, which is why the global cap exists.
 */
@Component
public class DemoLimiter {

    public enum Result { ALLOWED, RATE_LIMITED, DAILY_LIMIT, GLOBAL_LIMIT }

    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 24 * 60 * MINUTE_MS;
    private static final int PRUNE_THRESHOLD = 5_000;

    private final Clock clock;
    private final int perMinute;
    private final int perDay;
    private final int globalPerDay;

    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private LocalDate globalDay;
    private int globalCount;

    @Autowired
    public DemoLimiter(@Value("${demo.per-minute:5}") int perMinute,
                       @Value("${demo.per-day:30}") int perDay,
                       @Value("${demo.global-per-day:150}") int globalPerDay) {
        this(Clock.systemUTC(), perMinute, perDay, globalPerDay);
    }

    DemoLimiter(Clock clock, int perMinute, int perDay, int globalPerDay) {
        this.clock = clock;
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.globalPerDay = globalPerDay;
    }

    /** Records the request and returns ALLOWED, or says which limit refused it. */
    public synchronized Result tryAcquire(String callerKey) {
        long now = clock.millis();
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(globalDay)) {
            globalDay = today;
            globalCount = 0;
        }
        if (globalCount >= globalPerDay) {
            return Result.GLOBAL_LIMIT;
        }

        if (hits.size() > PRUNE_THRESHOLD) {
            prune(now);
        }
        Deque<Long> times = hits.computeIfAbsent(callerKey, key -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst() <= now - DAY_MS) {
            times.pollFirst();
        }
        if (times.size() >= perDay) {
            return Result.DAILY_LIMIT;
        }
        long lastMinute = times.stream().filter(t -> t > now - MINUTE_MS).count();
        if (lastMinute >= perMinute) {
            return Result.RATE_LIMITED;
        }

        times.addLast(now);
        globalCount++;
        return Result.ALLOWED;
    }

    private void prune(long now) {
        hits.values().removeIf(times -> times.isEmpty() || times.peekLast() <= now - DAY_MS);
    }
}
