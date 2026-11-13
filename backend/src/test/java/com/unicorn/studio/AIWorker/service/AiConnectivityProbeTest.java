package com.unicorn.studio.AIWorker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.controller.AiConfigController;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.AIWorker.vo.AiTestResultVO;
import com.unicorn.studio.admin.security.AdminSecurityConfig;
import com.unicorn.studio.admin.security.JwtAuthFilter;
import com.unicorn.studio.admin.security.JwtService;
import com.unicorn.studio.admin.service.AdminPermissionService;
import com.unicorn.studio.admin.service.FileService;
import com.unicorn.studio.common.RateLimitFilter;
import com.unicorn.studio.common.cache.RedisRateLimiter;
import com.unicorn.studio.config.AsyncConfig;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.service.PublicAuthService;
import com.unicorn.studio.vo.AuthPermissionsVO;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiConnectivityProbeTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AiConfigService service;
    private SysUser user;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        service = context.getBean(AiConfigService.class);
        user = new SysUser();
        user.setId(7L);
        user.setStatus(1);
        when(context.getBean(JwtService.class).parse("valid"))
                .thenReturn(Jwts.claims().subject("7").build());
        when(context.getBean(SysUserMapper.class).selectById(7L)).thenReturn(user);
        when(context.getBean(AdminPermissionService.class).loadPermissionsCached(user))
                .thenReturn(new AuthPermissionsVO(List.of(), List.of("ai:test")));
    }

    @AfterEach
    void teardown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void releasesRequestThreadAndAuthenticatesAsyncDispatch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(service.test(any())).thenAnswer(invocation -> {
            assertThat(Thread.currentThread().getName()).startsWith("ai-probe-");
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return success();
        });
        var pending = start();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(pending.getRequest().isAsyncStarted()).isTrue();
        assertThat(pending.getRequest().getAsyncContext().getTimeout()).isEqualTo(25_000);
        release.countDown();
        pending.getAsyncResult(3000);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.reply").value("连通"));
        verify(context.getBean(SysUserMapper.class), atLeast(2)).selectById(7L);
    }

    @Test
    void rejectsThirdTestImmediatelyWithoutSendingAnotherRequest() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(service.test(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return success();
        });
        var first = start();
        var second = start();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var third = start();
        mvc.perform(asyncDispatch(third)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value("已有 2 个连通性测试正在执行，请等待完成后再试"));
        verify(service, times(2)).test(any());
        assertThat(context.getBean(ThreadPoolTaskExecutor.class).getQueueSize()).isZero();
        release.countDown();
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
        mvc.perform(asyncDispatch(second)).andExpect(status().isOk());
        awaitIdle();
        var next = start();
        mvc.perform(asyncDispatch(next)).andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    void timeoutInterruptsWorkAndKeepsTimeoutResultEvenIfProviderReturnsLate() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(service.test(any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return success(); // 模拟供应商在取消后仍返回，不能覆盖超时结论。
        });
        var pending = start();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var async = (MockAsyncContext) pending.getRequest().getAsyncContext();
        for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
        assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("25 秒")));
        awaitIdle();
    }

    @Test
    void clientConnectionErrorInterruptsWork() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(service.test(any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return success();
        });
        var pending = start();
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var async = (MockAsyncContext) pending.getRequest().getAsyncContext();
        for (var listener : async.getListeners()) listener.onError(new AsyncEvent(async, new IOException("disconnected")));
        assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
        awaitIdle();
    }

    @Test
    void missingAuthenticationOrPermissionDoesNotStartAnyTest() throws Exception {
        mvc.perform(post("/api/admin/ai-config/test").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        when(context.getBean(AdminPermissionService.class).loadPermissionsCached(user))
                .thenReturn(new AuthPermissionsVO(List.of(), List.of("ai:view")));
        mvc.perform(post("/api/admin/ai-config/test").header("Authorization", "Bearer valid")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void disabledAccountCannotReadResultDuringAsyncDispatch() throws Exception {
        when(service.test(any())).thenReturn(success());
        var pending = start();
        pending.getAsyncResult(3000);
        user.setStatus(0);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isUnauthorized());
    }

    private MvcResult start() throws Exception {
        return mvc.perform(post("/api/admin/ai-config/test").header("Authorization", "Bearer valid")
                        .contentType("application/json").content("{\"model\":\"test\",\"apiUrl\":\"https://api.example.com/v1\"}"))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private AiTestResultVO success() {
        return AiTestResultVO.ok("test", "https://api.example.com/v1", 10, "连通");
    }

    private void awaitIdle() throws InterruptedException {
        var pool = context.getBean(ThreadPoolTaskExecutor.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (pool.getActiveCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(pool.getActiveCount()).isZero();
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(AdminSecurityConfig.class)
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean AiConfigService aiConfigService() { return mock(AiConfigService.class); }
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean SysUserMapper sysUserMapper() { return mock(SysUserMapper.class); }
        @Bean AdminPermissionService adminPermissionService() { return mock(AdminPermissionService.class); }
        @Bean JwtAuthFilter jwtAuthFilter(JwtService jwt, SysUserMapper users, AdminPermissionService permissions) {
            return new JwtAuthFilter(jwt, users, permissions);
        }
        @Bean RateLimitFilter rateLimitFilter(ObjectMapper mapper) {
            return new RateLimitFilter(mapper, mock(RedisRateLimiter.class), 5, 10, 10, 10, 5, 60, 5,
                    mock(PublicAuthService.class));
        }
        @Bean ThreadPoolTaskExecutor aiProbeExecutor() { return new AsyncConfig().aiProbeExecutor(); }
        @Bean AiConnectivityProbe connectivityProbe(AiConfigService service, ThreadPoolTaskExecutor pool) {
            return new AiConnectivityProbe(service, pool);
        }
        @Bean AiConfigController controller(AiConfigService service, AiConnectivityProbe probe) {
            return new AiConfigController(service, mock(AiToolRegistry.class), mock(FileService.class), probe);
        }
    }
}
