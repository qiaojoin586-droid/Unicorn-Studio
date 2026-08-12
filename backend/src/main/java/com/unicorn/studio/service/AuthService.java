package com.unicorn.studio.service;

import com.unicorn.studio.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;

@Service
public class AuthService {

    private final String learningPassword;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
            @Value("${app.security.learning-password}") String learningPassword,
            JwtTokenProvider jwtTokenProvider) {
        this.learningPassword = learningPassword;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Verify password using constant-time comparison (matching Node.js timingSafeEqual behavior).
     */
    public boolean verifyPassword(String password) {
        if (password == null || learningPassword == null) {
            return false;
        }
        return MessageDigest.isEqual(
                password.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                learningPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    public String generateToken() {
        return jwtTokenProvider.generateToken();
    }
}
