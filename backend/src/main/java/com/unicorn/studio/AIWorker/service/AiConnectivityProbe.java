package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiProbePolicy;
import com.unicorn.studio.AIWorker.dto.AiTestRequest;
import com.unicorn.studio.AIWorker.vo.AiTestResultVO;
import com.unicorn.studio.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** 探测从请求线程移出；不排队、不重试，超时或连接结束时中断在途任务。 */
@Service
public class AiConnectivityProbe {
    private static final Logger log = LoggerFactory.getLogger(AiConnectivityProbe.class);

    private final AiConfigService configService;
    private final TaskExecutor executor;

    public AiConnectivityProbe(AiConfigService configService,
                               @Qualifier("aiProbeExecutor") TaskExecutor executor) {
        this.configService = configService;
        this.executor = executor;
    }

    public DeferredResult<ApiResponse<AiTestResultVO>> test(AiTestRequest form) {
        long start = System.nanoTime();
        var result = new DeferredResult<ApiResponse<AiTestResultVO>>(AiProbePolicy.TOTAL_TIMEOUT.toMillis());
        var task = new FutureTask<Void>(() -> {
            try {
                result.setResult(ApiResponse.ok(configService.test(form)));
            } catch (Exception e) {
                result.setErrorResult(e);
            }
            return null;
        });
        result.onTimeout(() -> {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (result.setResult(failure(form, elapsed,
                    "测试总耗时超过 25 秒，已取消。请检查后端 DNS、网络或代理；网络正常时也可能是模型响应较慢"))) {
                task.cancel(true);
                log.warn("AI 连通性测试达到总时限：latency={}ms，已请求取消", elapsed);
            }
        });
        result.onError(error -> task.cancel(true));
        result.onCompletion(() -> task.cancel(true));
        try {
            // FutureTask 先创建再提交，避免任务在注册取消回调前就开始执行的竞态。
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            task.cancel(false);
            result.setResult(failure(form, 0, "已有 2 个连通性测试正在执行，请等待完成后再试"));
            log.warn("AI 连通性测试繁忙，未向供应商发送请求");
        }
        return result;
    }

    private ApiResponse<AiTestResultVO> failure(AiTestRequest form, long elapsed, String message) {
        String model = form == null || form.model() == null || form.model().isBlank() ? "-" : form.model();
        String url = form == null || form.apiUrl() == null || form.apiUrl().isBlank() ? "-" : form.apiUrl();
        return ApiResponse.ok(AiTestResultVO.fail(model, url, elapsed, message));
    }
}
