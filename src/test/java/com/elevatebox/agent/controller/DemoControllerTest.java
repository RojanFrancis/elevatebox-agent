package com.elevatebox.agent.controller;

import com.elevatebox.agent.service.CallAnalysis;
import com.elevatebox.agent.service.DemoLimiter;
import com.elevatebox.agent.service.GeminiService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.elevatebox.agent.service.GeminiService.LeadClassification.HOT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoControllerTest {

    private GeminiService gemini;
    private DemoLimiter limiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiService.class);
        limiter = mock(DemoLimiter.class);
        when(limiter.tryAcquire(any())).thenReturn(DemoLimiter.Result.ALLOWED);
        mvc = MockMvcBuilders.standaloneSetup(new DemoController(gemini, limiter, 100)).build();
    }

    private ResultActions post(String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/demo/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void analysesATranscript() throws Exception {
        when(gemini.analyzeCall(any())).thenReturn(
                new CallAnalysis(HOT, "asked the price", "a summary", "a recap", true, "tomorrow morning"));

        post("{\"transcript\":\"User: what is the price?\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("HOT"))
                .andExpect(jsonPath("$.reason").value("asked the price"))
                .andExpect(jsonPath("$.summary").value("a summary"))
                .andExpect(jsonPath("$.followUpRecap").value("a recap"))
                .andExpect(jsonPath("$.callbackRequested").value(true))
                .andExpect(jsonPath("$.callbackWhen").value("tomorrow morning"))
                .andExpect(jsonPath("$.note").value(Matchers.containsString("No WhatsApp")));
    }

    @Test
    void blankOrMissingTranscriptIsRejectedWithoutCallingGemini() throws Exception {
        post("{\"transcript\":\"   \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        post("{}").andExpect(status().isBadRequest());

        verifyNoInteractions(gemini);
    }

    @Test
    void tooLongTranscriptIsRejectedWithoutCallingGemini() throws Exception {
        post("{\"transcript\":\"" + "x".repeat(101) + "\"}").andExpect(status().isBadRequest());

        verifyNoInteractions(gemini);
    }

    @Test
    void rateLimitedCallersGetA429AndCostNothing() throws Exception {
        when(limiter.tryAcquire(any())).thenReturn(DemoLimiter.Result.RATE_LIMITED);

        post("{\"transcript\":\"hello\"}").andExpect(status().isTooManyRequests());

        verifyNoInteractions(gemini);
    }

    @Test
    void dailyLimitGivesA429() throws Exception {
        when(limiter.tryAcquire(any())).thenReturn(DemoLimiter.Result.DAILY_LIMIT);

        post("{\"transcript\":\"hello\"}").andExpect(status().isTooManyRequests());
    }

    @Test
    void globalLimitGivesA503() throws Exception {
        when(limiter.tryAcquire(any())).thenReturn(DemoLimiter.Result.GLOBAL_LIMIT);

        post("{\"transcript\":\"hello\"}").andExpect(status().isServiceUnavailable());

        verifyNoInteractions(gemini);
    }

    @Test
    void aGeminiFailureBecomesAFriendly503() throws Exception {
        when(gemini.analyzeCall(any())).thenThrow(new RuntimeException("429"));

        post("{\"transcript\":\"hello\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }
}
