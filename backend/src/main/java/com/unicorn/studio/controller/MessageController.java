package com.unicorn.studio.controller;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Message;
import com.unicorn.studio.repository.MessageRepository;
import com.unicorn.studio.service.SensitiveWordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 前台留言/联系表单（实施方案 §3.7）。 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageRepository messageRepository;
    private final SensitiveWordService sensitiveWordService;

    public MessageController(MessageRepository messageRepository,
                             SensitiveWordService sensitiveWordService) {
        this.messageRepository = messageRepository;
        this.sensitiveWordService = sensitiveWordService;
    }

    public record CreateRequest(@NotBlank(message = "姓名不能为空") String name,
                                String contact,
                                @NotBlank(message = "内容不能为空") String content,
                                String source) {
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest request,
                                                   HttpServletRequest httpRequest) {
        if (request.content().length() > 2000) {
            throw BusinessException.badRequest("留言内容不超过 2000 字");
        }
        String hit = sensitiveWordService.findSensitive(request.content());
        if (hit != null) {
            throw BusinessException.badRequest("留言包含敏感词，请修改后重试");
        }
        Message message = Message.builder()
                .name(request.name().trim())
                .contact(request.contact())
                .content(request.content().trim())
                .source(request.source() == null ? "contact_form" : request.source())
                .status("new")
                .ipHash(IpUtils.sha256(IpUtils.getClientIp(httpRequest)))
                .build();
        messageRepository.save(message);
        return ApiResponse.ok(Map.of("id", message.getId(), "message", "留言已提交，我们会尽快回复"));
    }
}
