package com.elevatebox.agent.service;

import com.elevatebox.agent.persistence.CallRecord;
import com.elevatebox.agent.persistence.CallRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves and reads call outcomes.
 *
 * <p>The database must never be able to break the call pipeline: if it is down, a call is still
 * analysed and the WhatsApps still go out. So {@link #save} and {@link #exists} log failures
 * instead of throwing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallRecordService {

    static final int MAX_LIMIT = 200;

    private final CallRecordRepository repository;

    /**
     * Stores the outcome of a pipeline run. Never throws.
     *
     * @param callId Vapi's call ID, or null for a manual run
     * @param source where the call came from, for example "vapi" or "manual"
     * @param result the pipeline outcome
     */
    public void save(String callId, String source, LeadClassifierService.LeadResult result) {
        try {
            CallRecord record = new CallRecord();
            record.setCallId(callId);
            record.setSource(source);
            record.setClassification(result.classification());
            record.setReason(truncate(result.reason(), 1000));
            record.setSummary(truncate(result.summary(), 4000));
            record.setCallbackRequested(result.callbackRequested());
            record.setCallbackWhen(truncate(result.callbackTime(), 255));
            record.setFailedSteps(truncate(String.join(",", result.failedSteps()), 255));
            repository.save(record);
        } catch (Exception e) {
            log.error("Could not save call record (callId={}): {}", callId, e.getMessage());
        }
    }

    /** True if this call was already stored. Returns false, never throws, if the database is down. */
    public boolean exists(String callId) {
        try {
            return repository.existsByCallId(callId);
        } catch (Exception e) {
            log.warn("Could not check for a duplicate call (callId={}): {}", callId, e.getMessage());
            return false;
        }
    }

    /** The newest records first, with the limit kept between 1 and {@value #MAX_LIMIT}. */
    public List<CallRecord> recent(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, size));
    }

    /** Totals, overall and per label. */
    public Map<String, Long> stats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", repository.count());
        for (GeminiService.LeadClassification label : GeminiService.LeadClassification.values()) {
            stats.put(label.name(), repository.countByClassification(label));
        }
        return stats;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
