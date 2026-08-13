package com.unicorn.studio.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件通知（可选）：未配置 SMTP_* 时仅记日志，不阻塞业务。
 * 留言回复等通知开关存 site_settings（notify.mail_enabled）。
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(JavaMailSender mailSender,
                        @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.from = from;
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
        return from != null && !from.isBlank();
    }
}
