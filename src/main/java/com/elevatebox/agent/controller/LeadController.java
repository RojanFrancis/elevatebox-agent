package com.elevatebox.agent.controller;

import com.elevatebox.agent.persistence.CallRecord;
import com.elevatebox.agent.persistence.StorageInfo;
import com.elevatebox.agent.service.CallRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /api/leads returns the newest analysed calls, totals per label and which database is in use. It needs the API key
 * like every other {@code /api} route, because the rows describe real conversations.
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final CallRecordService callRecords;
    private final StorageInfo storageInfo;

    @GetMapping
    public Map<String, Object> leads(@RequestParam(defaultValue = "50") int limit) {
        List<LeadView> leads = callRecords.recent(limit).stream().map(LeadView::from).toList();
        return Map.of("stats", callRecords.stats(), "leads", leads, "storage", storageInfo.describe());
    }

    /** What the API exposes of a stored call. */
    public record LeadView(Long id, String callId, String source, Instant createdAt,
                           String classification, String reason, String summary,
                           boolean callbackRequested, String callbackWhen, String failedSteps) {

        static LeadView from(CallRecord r) {
            return new LeadView(r.getId(), r.getCallId(), r.getSource(), r.getCreatedAt(),
                    r.getClassification() == null ? null : r.getClassification().name(),
                    r.getReason(), r.getSummary(), r.isCallbackRequested(),
                    r.getCallbackWhen(), r.getFailedSteps());
        }
    }
}
