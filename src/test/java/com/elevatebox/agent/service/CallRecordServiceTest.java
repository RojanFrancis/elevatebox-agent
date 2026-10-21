package com.elevatebox.agent.service;

import com.elevatebox.agent.persistence.CallRecord;
import com.elevatebox.agent.persistence.CallRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static com.elevatebox.agent.service.GeminiService.LeadClassification.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CallRecordServiceTest {

    private CallRecordRepository repository;
    private CallRecordService service;

    @BeforeEach
    void setUp() {
        repository = mock(CallRecordRepository.class);
        service = new CallRecordService(repository);
    }

    private static LeadClassifierService.LeadResult result(String reason, String summary) {
        return new LeadClassifierService.LeadResult(HOT, reason, summary, true, "tomorrow morning", List.of("follow-up", "summary"));
    }

    @Test
    void savesTheOutcome() {
        service.save("call-1", "vapi", result("because", "a summary"));

        ArgumentCaptor<CallRecord> saved = ArgumentCaptor.forClass(CallRecord.class);
        verify(repository).save(saved.capture());
        CallRecord record = saved.getValue();
        assertEquals("call-1", record.getCallId());
        assertEquals("vapi", record.getSource());
        assertEquals(HOT, record.getClassification());
        assertEquals("because", record.getReason());
        assertEquals("a summary", record.getSummary());
        assertTrue(record.isCallbackRequested());
        assertEquals("tomorrow morning", record.getCallbackWhen());
        assertEquals("follow-up,summary", record.getFailedSteps());
    }

    @Test
    void overlongTextIsCutToTheColumnSize() {
        service.save("call-1", "vapi", result("r".repeat(2000), "s".repeat(5000)));

        ArgumentCaptor<CallRecord> saved = ArgumentCaptor.forClass(CallRecord.class);
        verify(repository).save(saved.capture());
        assertEquals(1000, saved.getValue().getReason().length());
        assertEquals(4000, saved.getValue().getSummary().length());
    }

    @Test
    void aDatabaseFailureNeverBreaksTheCaller() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.save("call-1", "vapi", result("because", "a summary")));
    }

    @Test
    void existsDelegatesToTheRepository() {
        when(repository.existsByCallId("call-1")).thenReturn(true);

        assertTrue(service.exists("call-1"));
        assertFalse(service.exists("call-2"));
    }

    @Test
    void existsIsFalseNotAnErrorWhenTheDatabaseIsDown() {
        when(repository.existsByCallId("call-1")).thenThrow(new RuntimeException("db down"));

        assertFalse(service.exists("call-1"));
    }

    @Test
    void recentKeepsTheLimitInRange() {
        service.recent(9999);
        service.recent(0);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAllByOrderByCreatedAtDesc(page.capture());
        assertEquals(CallRecordService.MAX_LIMIT, page.getAllValues().get(0).getPageSize());
        assertEquals(1, page.getAllValues().get(1).getPageSize());
    }

    @Test
    void statsHaveATotalAndEveryLabel() {
        when(repository.count()).thenReturn(5L);
        when(repository.countByClassification(HOT)).thenReturn(2L);

        Map<String, Long> stats = service.stats();

        assertEquals(5L, stats.get("total"));
        assertEquals(2L, stats.get("HOT"));
        assertEquals(0L, stats.get("UNKNOWN"));
        assertTrue(stats.keySet().containsAll(List.of("total", "HOT", "WARM", "COLD", "UNKNOWN")));
    }
}
