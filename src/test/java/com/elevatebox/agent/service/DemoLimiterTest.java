package com.elevatebox.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static com.elevatebox.agent.service.DemoLimiter.Result.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DemoLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
    }

    @Test
    void perMinuteLimitRefusesThenRecovers() {
        DemoLimiter limiter = new DemoLimiter(clock, 2, 100, 1000);

        assertEquals(ALLOWED, limiter.tryAcquire("a"));
        assertEquals(ALLOWED, limiter.tryAcquire("a"));
        assertEquals(RATE_LIMITED, limiter.tryAcquire("a"));

        clock.advance(Duration.ofSeconds(61));
        assertEquals(ALLOWED, limiter.tryAcquire("a"));
    }

    @Test
    void perDayLimitRefusesThenRecoversTheNextDay() {
        DemoLimiter limiter = new DemoLimiter(clock, 100, 3, 1000);

        for (int i = 0; i < 3; i++) {
            assertEquals(ALLOWED, limiter.tryAcquire("a"));
        }
        assertEquals(DAILY_LIMIT, limiter.tryAcquire("a"));

        clock.advance(Duration.ofHours(25));
        assertEquals(ALLOWED, limiter.tryAcquire("a"));
    }

    @Test
    void globalCapProtectsTheQuotaAcrossCallers() {
        DemoLimiter limiter = new DemoLimiter(clock, 100, 100, 2);

        assertEquals(ALLOWED, limiter.tryAcquire("a"));
        assertEquals(ALLOWED, limiter.tryAcquire("b"));
        assertEquals(GLOBAL_LIMIT, limiter.tryAcquire("c"));

        clock.advance(Duration.ofHours(25));
        assertEquals(ALLOWED, limiter.tryAcquire("c"));
    }

    @Test
    void callersAreCountedSeparately() {
        DemoLimiter limiter = new DemoLimiter(clock, 1, 100, 1000);

        assertEquals(ALLOWED, limiter.tryAcquire("a"));
        assertEquals(RATE_LIMITED, limiter.tryAcquire("a"));
        assertEquals(ALLOWED, limiter.tryAcquire("b"));
    }

    @Test
    void refusedRequestsDoNotUseUpTheAllowance() {
        DemoLimiter limiter = new DemoLimiter(clock, 1, 100, 1000);

        assertEquals(ALLOWED, limiter.tryAcquire("a"));
        for (int i = 0; i < 5; i++) {
            assertEquals(RATE_LIMITED, limiter.tryAcquire("a"));
        }
        clock.advance(Duration.ofSeconds(61));
        assertEquals(ALLOWED, limiter.tryAcquire("a"));
    }
}
