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

        MockHttpServletRequest first = buildSemanticRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        MockHttpServletRequest second = buildSemanticRequest();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentAsString()).contains("Rate limit exceeded");
    }

    private MockHttpServletRequest buildSemanticRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/users/recommendations/semantic/scored");
        request.addHeader("User-Agent", "rate-limit-test");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
