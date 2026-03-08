package com.animetracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class RequestRateLimitingFilterTest {

    @Test
    void blocksRequestWhenLimitExceeded() throws Exception {
        RequestRateLimitingFilter filter = new RequestRateLimitingFilter();
        configureDefaults(filter);

        MockHttpServletRequest first = buildSemanticRequest(null, "127.0.0.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        MockHttpServletRequest second = buildSemanticRequest(null, "127.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void usesPenultimateForwardedForHopToReduceSpoofing() throws Exception {
        RequestRateLimitingFilter filter = new RequestRateLimitingFilter();
        configureDefaults(filter);

        MockHttpServletRequest first = buildSemanticRequest("1.1.1.1, 9.9.9.9, 10.0.0.1", "127.0.0.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        // Spoofed first token changes, but penultimate stays the same.
        MockHttpServletRequest second = buildSemanticRequest("2.2.2.2, 9.9.9.9, 10.0.0.1", "127.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
    }

    @Test
    void usesSingleForwardedForValueWhenOnlyOneHopPresent() throws Exception {
        RequestRateLimitingFilter filter = new RequestRateLimitingFilter();
        configureDefaults(filter);

        MockHttpServletRequest first = buildSemanticRequest("9.9.9.9", "127.0.0.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        MockHttpServletRequest second = buildSemanticRequest("9.9.9.9", "127.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
    }

    @Test
    void fallsBackToRemoteAddressWhenForwardedForInvalid() throws Exception {
        RequestRateLimitingFilter filter = new RequestRateLimitingFilter();
        configureDefaults(filter);

        MockHttpServletRequest first = buildSemanticRequest("totally-invalid", "10.10.10.10");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        MockHttpServletRequest second = buildSemanticRequest("still-invalid", "10.10.10.10");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
    }

    private void configureDefaults(RequestRateLimitingFilter filter) {
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "windowSeconds", 300);
        ReflectionTestUtils.setField(filter, "cleanupIntervalSeconds", 60);
        ReflectionTestUtils.setField(filter, "trustForwardedFor", true);
        ReflectionTestUtils.setField(filter, "anonymousGlobalLimit", 100);
        ReflectionTestUtils.setField(filter, "authenticatedGlobalLimit", 100);
        ReflectionTestUtils.setField(filter, "searchLimit", 100);
        ReflectionTestUtils.setField(filter, "recommendationLimit", 1);
        ReflectionTestUtils.setField(filter, "loginLimit", 100);
        ReflectionTestUtils.setField(filter, "registerLimit", 100);
    }

    private MockHttpServletRequest buildSemanticRequest(String xForwardedFor, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/users/recommendations/semantic/scored");
        request.addHeader("User-Agent", "rate-limit-test");
        if (xForwardedFor != null) {
            request.addHeader("X-Forwarded-For", xForwardedFor);
        }
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
