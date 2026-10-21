package com.elevatebox.agent.controller;

import com.elevatebox.agent.persistence.CallRecord;
import com.elevatebox.agent.service.CallRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.elevatebox.agent.service.GeminiService.LeadClassification.HOT;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeadControllerTest {

    @Test
    void returnsTheNewestCallsAndTheTotals() throws Exception {
        CallRecordService service = mock(CallRecordService.class);
        CallRecord record = new CallRecord();
        record.setId(7L);
        record.setCallId("call-1");
        record.setSource("vapi");
        record.setCreatedAt(Instant.parse("2026-10-21T10:00:00Z"));
        record.setClassification(HOT);
        record.setReason("asked the price");
        record.setSummary("a summary");
        record.setCallbackRequested(true);
        record.setCallbackWhen("tomorrow morning");
        record.setFailedSteps("");
        when(service.recent(5)).thenReturn(List.of(record));
        when(service.stats()).thenReturn(Map.of("total", 3L, "HOT", 1L));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LeadController(service)).build();

        mvc.perform(get("/api/leads?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.total").value(3))
                .andExpect(jsonPath("$.leads[0].callId").value("call-1"))
                .andExpect(jsonPath("$.leads[0].classification").value("HOT"))
                .andExpect(jsonPath("$.leads[0].callbackWhen").value("tomorrow morning"));
    }

    @Test
    void defaultsToTheFiftyNewestCalls() throws Exception {
        CallRecordService service = mock(CallRecordService.class);
        when(service.recent(50)).thenReturn(List.of());
        when(service.stats()).thenReturn(Map.of("total", 0L));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LeadController(service)).build();

        mvc.perform(get("/api/leads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leads").isEmpty());

        verify(service).recent(50);
    }
}
