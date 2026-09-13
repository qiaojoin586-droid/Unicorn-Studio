package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.MessageMapper;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.service.EmailService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 后台留言管理（实施方案 §3.4）：列表 / 回复（邮件通知）/ 状态 / 删除。 */
@Service
public class MessageAdminService {

    private final MessageMapper messageMapper;
    private final EmailService emailService;

    public MessageAdminService(MessageMapper messageMapper, EmailService emailService) {
        this.messageMapper = messageMapper;
        this.emailService = emailService;
    }

    public PageResult<Message> list(String status, String source, String keyword, int page, int size) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Message::getStatus, status);
        }
        if (source != null && !source.isBlank()) {
            wrapper.eq(Message::getSource, source);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(Message::getName, like)
                    .or().like(Message::getContact, like)
                    .or().like(Message::getContent, like));
        }
        wrapper.orderByDesc(Message::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        return PageResult.of(messageMapper.selectPage(new Page<>(page + 1, size), wrapper));
    }

    public Message detail(Long id) {
        return getOrThrow(id);
    }

    /**
     * 回复：状态转 replied，触发邮件通知（若留了联系邮箱）。
     * <p>
     * <b>发信必须走 {@code sendAfterCommit}</b>（B-07）：SMTP 是外部网络 IO，
     * 在事务内同步发送会占着 Druid 连接与请求线程数百毫秒到数秒，
     * 且 SMTP 失败会连带回滚留言的 replied 状态 —— 管理员看到「回复失败」，
     * 但邮件可能已经发出去了。延后到提交后 + 独立线程池，两者彻底解耦。
     */
    @Transactional
    public void reply(Long id, String content, Long operatorId) {
        Message message = getOrThrow(id);
        message.setReplyContent(content);
        message.setStatus("replied");
        message.setRepliedBy(operatorId);
        message.setRepliedAt(LocalDateTime.now());
        messageMapper.updateById(message);

        if (message.getContact() != null && message.getContact().contains("@")) {
            emailService.sendAfterCommit(message.getContact().trim(),
                    "独角兽工作室：您的留言已回复",
                    "您提交的留言「" + truncate(message.getContent(), 50) + "」已收到回复：\n\n"
                            + content + "\n\n—— 独角兽工作室");
        }
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        if (!List.of("replied", "closed").contains(status)) {
            throw BusinessException.badRequest("非法状态: " + status);
        }
        Message message = getOrThrow(id);
        message.setStatus(status);
        messageMapper.updateById(message);
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        messageMapper.deleteById(id);
    }

    private Message getOrThrow(Long id) {
        return Optional.ofNullable(messageMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("留言不存在"));
    }

    private String truncate(String text, int max) {
        return text == null ? "" : (text.length() > max ? text.substring(0, max) + "…" : text);
    }
}
