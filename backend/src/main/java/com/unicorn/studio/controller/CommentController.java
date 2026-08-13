package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Comment;
import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.service.CommentService;
import com.unicorn.studio.service.PublicAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 前台评论接口（实施方案 §3.7）。 */
@RestController
@RequestMapping("/api/comments")
public class CommentController {

    private final CommentService commentService;
    private final PublicAuthService publicAuthService;

    public CommentController(CommentService commentService, PublicAuthService publicAuthService) {
        this.commentService = commentService;
        this.publicAuthService = publicAuthService;
    }

    public record CreateRequest(@NotBlank(message = "评论对象不能为空") String targetType,
                                @NotBlank(message = "评论对象 id 不能为空") Long targetId,
                                @NotBlank(message = "评论内容不能为空") String content,
                                String guestNickname,
                                Long parentId) {
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam String targetType,
            @RequestParam Long targetId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(commentService.listApproved(targetType, targetId, page - 1, size));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateRequest request,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                                   HttpServletRequest httpRequest) {
        PublicUser user = publicAuthService.resolveFromHeader(authorization);
        Comment comment = commentService.create(request.targetType(), request.targetId(),
                request.content(), request.guestNickname(), request.parentId(), user, httpRequest);
        return ApiResponse.ok(Map.of(
                "id", comment.getId(),
                "status", comment.getStatus(),
                "message", "评论已提交，审核通过后展示"));
    }
}
