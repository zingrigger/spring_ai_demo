package com.example.weather.service.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public final class UserContextLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(UserContextLoggingFilter.class);
    private static final String X_USER_ID = "X-User-Id";
    private static final String X_USER_TENANT = "X-User-Tenant";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userId = request.getHeader(X_USER_ID);
        String tenantId = request.getHeader(X_USER_TENANT);
        if (userId != null || tenantId != null) {
            logger.info("收到用户上下文: X-User-Id={}, X-User-Tenant={}", userId, tenantId);
        }
        filterChain.doFilter(request, response);
    }
}
