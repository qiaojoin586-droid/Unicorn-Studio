package com.unicorn.studio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.GlobalExceptionHandler;
import com.unicorn.studio.common.RateLimitFilter;
import com.unicorn.studio.common.cache.RedisRateLimiter;
import com.unicorn.studio.controller.PublicAuthController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RegistrationCodeEndpointTest {
    private final PublicAuthService auth = mock(PublicAuthService.class);

    @Test
    void oldIpBucketCannotBlockSmsEndpoint() throws Exception {
        var limiter = mock(RedisRateLimiter.class);
        when(limiter.isAllowed(anyString(), anyLong(), any())).thenReturn(false);
        var filter = new RateLimitFilter(new ObjectMapper(), limiter, 5, 10, 10, 10, 5, 60, 5, auth);
        var request = new MockHttpServletRequest("POST", "/api/auth/register/code");
        var response = new MockHttpServletResponse();
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        verifyNoInteractions(limiter);
    }

    @Test
    void registrationSubmissionKeepsSeparateIpLimit() throws Exception {
        var limiter = mock(RedisRateLimiter.class);
        var filter = new RateLimitFilter(new ObjectMapper(), limiter, 5, 10, 10, 10, 5, 60, 5, auth);
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/register"), response,
                (req, res) -> fail("Registration should still be limited"));
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void tenthDeliveryReturnsQuotaAndWaitMetadata() throws Exception {
        when(auth.sendRegistrationCode("13900000000")).thenReturn(new RegistrationCodeService.SendResult(2520, 0));
        MockMvcBuilders.standaloneSetup(new PublicAuthController(auth)).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/api/auth/register/code").contentType("application/json").content("{\"phone\":\"13900000000\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingHourlySends").value(0))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(2520));
    }

    @Test
    void quotaFailureReturnsMessageRetryHeaderAndBodyMetadata() throws Exception {
        String message = "该手机号每小时最多发送10条验证码，当前额度已用完，请在42分钟后重试";
        when(auth.sendRegistrationCode("13900000000")).thenThrow(new RegistrationCodeLimitException(2520, message));
        MockMvcBuilders.standaloneSetup(new PublicAuthController(auth)).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/api/auth/register/code").contentType("application/json").content("{\"phone\":\"13900000000\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2520"))
                .andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.data.retryAfterSeconds").value(2520));
    }
}
