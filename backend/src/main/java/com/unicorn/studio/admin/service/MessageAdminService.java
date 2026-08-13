package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.repository.MessageRepository;
import com.unicorn.studio.service.EmailService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 后台留言管理（实施方案 §3.4）：列表 / 回复（邮件通知）/ 状态 / 删除。 */
@Service
public class MessageAdminService {

    private final MessageRepository messageRepository;
    private final EmailService emailService;

    public MessageAdminService(MessageRepository messageRepository, EmailService emailService) {
        this.messageRepository = messageRepository;
        this.emailService = emailService;
    }

    public PageResult<Message> list(String status, String source, String keyword, int page, int size) {
        Specification<Message> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (source != null && !source.isBlank()) {
                predicates.add(cb.equal(root.get("source"), source));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("name"), like),
                        cb.like(root.get("contact"), like),
                        cb.like(root.get("content"), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Message> messages = messageRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.of(messages);
    }

    public Message detail(Long id) {
        return getOrThrow(id);
    }

    /** 回复：状态转 replied，触发邮件通知（若留了联系邮箱）。 */
    @Transactional
    public void reply(Long id, String content, Long operatorId) {
        Message message = getOrThrow(id);
        message.setReplyContent(content);
        message.setStatus("replied");
        message.setRepliedBy(operatorId);
        message.setRepliedAt(LocalDateTime.now());
        messageRepository.save(message);

        if (message.getContact() != null && message.getContact().contains("@")) {
            emailService.send(message.getContact().trim(),
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
        messageRepository.save(message);
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        messageRepository.deleteById(id);
    }

    private Message getOrThrow(Long id) {
        return messageRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("留言不存在"));
    }

    private String truncate(String text, int max) {
        return text == null ? "" : (text.length() > max ? text.substring(0, max) + "…" : text);
    }
}
