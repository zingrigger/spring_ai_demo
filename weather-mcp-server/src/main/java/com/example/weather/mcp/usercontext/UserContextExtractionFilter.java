package com.example.weather.mcp.usercontext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class UserContextExtractionFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(UserContextExtractionFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String X_USER_ID = "X-User-Id";
    private static final String X_USER_TENANT = "X-User-Tenant";

    private final String source;
    private final JwtTokenParser jwtTokenParser;

    public UserContextExtractionFilter(@Value("${user-context.source}") String source,
                                       JwtTokenParser jwtTokenParser) {
        this.source = source;
        this.jwtTokenParser = jwtTokenParser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if ("bearer-token".equals(source)) {
                extractFromBearerToken(request);
            } else {
                extractFromExplicitHeaders(request);
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private void extractFromBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            logger.debug("请求缺少 Authorization Bearer 头，跳过用户上下文提取");
            return;
        }
        jwtTokenParser.parse(authorization.substring(BEARER_PREFIX.length()))
                .ifPresent(UserContextHolder::set);
    }

    private void extractFromExplicitHeaders(HttpServletRequest request) {
        String userId = request.getHeader(X_USER_ID);
        String tenantId = request.getHeader(X_USER_TENANT);
        if (userId == null && tenantId == null) {
            logger.debug("请求缺少 X-User-Id/X-User-Tenant 头，跳过用户上下文提取");
            return;
        }
        UserContextHolder.set(new UserContext(userId, tenantId));
    }
}
