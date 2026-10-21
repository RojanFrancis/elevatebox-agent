package com.elevatebox.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyFilterTest {

    private static final String API_KEY = "a".repeat(32);
    private static final String HOOK_SECRET = "b".repeat(32);

    private final ApiKeyFilter filter = new ApiKeyFilter(API_KEY, HOOK_SECRET);

    private record Outcome(int status, boolean reachedController) {}

    private Outcome call(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return new Outcome(res.getStatus(), chain.getRequest() != null);
    }

    private MockHttpServletRequest post(String path) {
        return new MockHttpServletRequest("POST", path);
    }

    @Test
    void healthCheckIsOpen() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/call/health");
        assertTrue(call(req).reachedController());
    }

    @Test
    void apiEndpointWithoutKeyIsRejected() throws Exception {
        Outcome out = call(post("/api/call/initiate"));
        assertEquals(401, out.status());
        assertFalse(out.reachedController());
    }

    @Test
    void apiEndpointWithWrongKeyIsRejected() throws Exception {
        MockHttpServletRequest req = post("/api/call/classify");
        req.addHeader("X-API-Key", "wrong-key-wrong-key-wrong-key");
        assertEquals(401, call(req).status());
    }

    @Test
    void apiEndpointWithCorrectKeyPasses() throws Exception {
        MockHttpServletRequest req = post("/api/call/classify");
        req.addHeader("X-API-Key", API_KEY);
        assertTrue(call(req).reachedController());
    }

    @Test
    void webhookDoesNotAcceptTheApiKey() throws Exception {
        MockHttpServletRequest req = post("/api/call/webhook");
        req.addHeader("X-API-Key", API_KEY);
        assertEquals(401, call(req).status());
    }

    @Test
    void webhookAcceptsVapiSecretHeader() throws Exception {
        MockHttpServletRequest req = post("/api/call/webhook");
        req.addHeader("X-Vapi-Secret", HOOK_SECRET);
        assertTrue(call(req).reachedController());
    }

    @Test
    void webhookAcceptsBearerToken() throws Exception {
        MockHttpServletRequest req = post("/api/call/webhook");
        req.addHeader("Authorization", "Bearer " + HOOK_SECRET);
        assertTrue(call(req).reachedController());
    }

    @Test
    void apiEndpointDoesNotAcceptTheWebhookSecret() throws Exception {
        MockHttpServletRequest req = post("/api/call/initiate");
        req.addHeader("X-API-Key", HOOK_SECRET);
        assertEquals(401, call(req).status());
    }

    @Test
    void onlyTheDemoAnalyzeRouteIsPublic() throws Exception {
        assertTrue(call(post("/api/demo/analyze")).reachedController());
        assertEquals(401, call(post("/api/demo/other")).status());
        assertEquals(401, call(new MockHttpServletRequest("GET", "/api/leads")).status());
    }

    @Test
    void refusesToStartWithWeakSecrets() {
        assertThrows(IllegalStateException.class, () -> new ApiKeyFilter("short", HOOK_SECRET));
        assertThrows(IllegalStateException.class, () -> new ApiKeyFilter(API_KEY, ""));
    }
}
