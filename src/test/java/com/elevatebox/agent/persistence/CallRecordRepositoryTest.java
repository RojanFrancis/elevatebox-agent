package com.elevatebox.agent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static com.elevatebox.agent.service.GeminiService.LeadClassification;
import static com.elevatebox.agent.service.GeminiService.LeadClassification.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class CallRecordRepositoryTest {

    @Autowired
    private CallRecordRepository repository;

    private static CallRecord record(String callId, LeadClassification label, Instant at) {
        CallRecord r = new CallRecord();
        r.setCallId(callId);
        r.setSource("vapi");
        r.setClassification(label);
        r.setCreatedAt(at);
        r.setReason("because");
        r.setSummary("a summary");
        return r;
    }

    @Test
    void newestCallsComeFirst() {
        Instant base = Instant.parse("2026-10-21T10:00:00Z");
        repository.save(record("old", COLD, base));
        repository.save(record("newest", HOT, base.plusSeconds(120)));
        repository.save(record("middle", WARM, base.plusSeconds(60)));

        List<CallRecord> page = repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 2));

        assertEquals(List.of("newest", "middle"), page.stream().map(CallRecord::getCallId).toList());
    }

    @Test
    void existsByCallIdFindsStoredCallsOnly() {
        repository.save(record("call-1", HOT, Instant.now()));

        assertTrue(repository.existsByCallId("call-1"));
        assertFalse(repository.existsByCallId("call-2"));
    }

    @Test
    void aCallIdCanOnlyBeStoredOnce() {
        repository.saveAndFlush(record("dup", HOT, Instant.now()));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(record("dup", WARM, Instant.now())));
    }

    @Test
    void manualRecordsWithoutACallIdAreAllowedSeveralTimes() {
        repository.saveAndFlush(record(null, HOT, Instant.now()));
        repository.saveAndFlush(record(null, COLD, Instant.now()));

        assertEquals(2, repository.count());
    }

    @Test
    void countsPerLabel() {
        repository.save(record("a", HOT, Instant.now()));
        repository.save(record("b", HOT, Instant.now()));
        repository.save(record("c", COLD, Instant.now()));

        assertEquals(2, repository.countByClassification(HOT));
        assertEquals(1, repository.countByClassification(COLD));
        assertEquals(0, repository.countByClassification(UNKNOWN));
    }

    @Test
    void creationTimeIsStampedWhenMissing() {
        CallRecord saved = repository.saveAndFlush(record("stamped", WARM, null));

        assertNotNull(saved.getCreatedAt());
    }
}
