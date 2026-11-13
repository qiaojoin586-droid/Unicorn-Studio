package com.unicorn.studio.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

/**
 * 邮件通知（可选）：未配置 SMTP_* 时仅记日志，不阻塞业务。
 * 留言回复等通知开关存 site_settings（notify.mail_enabled）。
 * <p>
 * <b>发信有两件事必须避免（B-07）</b>：
 * <ol>
 *   <li><b>不能在数据库事务内发</b> —— SMTP 是外部网络 IO（数百毫秒到数秒），
 *       这段时间里一条 Druid 连接与一个请求线程都被占着；而且 SMTP 抛异常会
 *       <b>连带回滚业务的 DB 写</b> —— 管理员看到「回复失败」，但邮件可能已经发出去了。
 *       故对外提供 {@link #sendAfterCommit}。</li>
 *   <li><b>不能没有超时</b> —— JavaMail 默认是<b>无限等待</b>。SMTP 服务器接受 TCP 连接后
 *       不响应（网络黑洞 / 防火墙丢包 / 对端假死）时请求会永久挂起并占死一条连接。
 *       超时由 {@code application.yml} 的 {@code spring.mail.properties.mail.smtp.*} 配置。</li>
 * </ol>
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final Executor mailExecutor;

    public EmailService(@Nullable JavaMailSender mailSender,
                        @Value("${spring.mail.username:}") String from,
                        @Qualifier("mailExecutor") Executor mailExecutor) {
        this.mailSender = mailSender;
        this.from = from;
        this.mailExecutor = mailExecutor;
    }

    /**
     * 在<b>当前事务提交之后</b>、于独立线程池中异步发信。
     * <p>
     * 使用场景：由带 {@code @Transactional} 的业务方法调用（如留言回复）。
     * 事务内只做 DB 写；发信延后到提交成功之后，因此
     * 「邮件失败」与「业务回滚」彻底解耦 —— 邮件发不出去不影响留言回复本身成功，
     * 业务回滚也不会留下一封「回复了但内容没落库」的邮件。
     * <p>
     * 无事务时直接异步发送（调用方不在事务里，语义相同）。
     */
    public void sendAfterCommit(String to, String subject, String text) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitAsync(to, subject, text);
                }
            });
        } else {
            submitAsync(to, subject, text);
        }
    }

    private void submitAsync(String to, String subject, String text) {
        try {
            mailExecutor.execute(() -> send(to, subject, text));
        } catch (Exception e) {
            // 提交被拒（队列满）不应影响已经提交成功的事务，仅记日志
            log.warn("邮件任务提交失败（不影响业务）: to={}, 原因={}", to, e.getMessage());
        }
    }

    public void send(String to, String subject, String text) {
        if (!isConfigured()) {
            log.info("[邮件未配置] 模拟发送 to={}, subject={}", to, subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("邮件发送失败 to={}: {}", to, e.getMessage());
        }
    }

    private boolean isConfigured() {
        return mailSender != null && from != null && !from.isBlank();
    }
}
