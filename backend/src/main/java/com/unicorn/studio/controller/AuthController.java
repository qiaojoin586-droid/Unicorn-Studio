package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.LearningResource;
import com.unicorn.studio.model.LoginRequest;
import com.unicorn.studio.security.RateLimitFilter;
import com.unicorn.studio.service.AuthService;
import com.unicorn.studio.service.LearningService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learning")
public class AuthController {

    private static final String COOKIE_NAME = "learning_session";

    private final AuthService authService;
    private final LearningService learningService;
    private final RateLimitFilter rateLimitFilter;
    private final long sessionTtlMs;
    private final boolean secureCookie;

    public AuthController(
            AuthService authService,
            LearningService learningService,
            RateLimitFilter rateLimitFilter,
            @Value("${app.security.jwt-ttl-ms:28800000}") long sessionTtlMs,
            @Value("${app.security.cookie-secure:true}") boolean secureCookie) {
        this.authService = authService;
        this.learningService = learningService;
        this.rateLimitFilter = rateLimitFilter;
        this.sessionTtlMs = sessionTtlMs;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse response) {

        // Rate limiting check
        if (rateLimitFilter.isLimited(httpRequest)) {
            long retryAfter = rateLimitFilter.getRetryAfterSeconds(httpRequest);
            return ResponseEntity.status(429)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(ApiResponse.error(429, "尝试次数过多，请稍后再试"));
        }

        // Verify password
        if (!authService.verifyPassword(request.getPassword())) {
            rateLimitFilter.recordFailure(httpRequest);
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "密码错误，请重试"));
        }

        // Clear rate limit on success
        rateLimitFilter.clearFailure(httpRequest);

        // Generate JWT and set cookie
        String token = authService.generateToken();
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) (sessionTtlMs / 1000));
        cookie.setSecure(secureCookie);
        // SameSite=Lax needs to be set via header since Cookie API doesn't support it
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=" + token +
                "; Max-Age=" + (sessionTtlMs / 1000) +
                "; Path=/; HttpOnly; SameSite=Lax" +
                (secureCookie ? "; Secure" : ""));

        List<LearningResource> resources = learningService.findAll();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("resources", resources)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addHeader("Set-Cookie",
                COOKIE_NAME + "=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax" +
                (secureCookie ? "; Secure" : ""));

        return ResponseEntity.ok(ApiResponse.ok());
    }
}
