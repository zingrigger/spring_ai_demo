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
import org.springframework.util.StringUtils;
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
        if (!"bearer-token".equals(source) && !"explicit-headers".equals(source)) {
            throw new IllegalArgumentException(
                    "不支持的 user-context.source: '" + source + "'（仅支持 bearer-token 或 explicit-headers）");
        }
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
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            logger.debug("请求缺少 Authorization Bearer 头，跳过用户上下文提取");
            return;
        }
        jwtTokenParser.parse(authorization.substring(BEARER_PREFIX.length()).strip())
                .ifPresent(UserContextHolder::set);
    }

    private void extractFromExplicitHeaders(HttpServletRequest request) {
        String userId = request.getHeader(X_USER_ID);
        String tenantId = request.getHeader(X_USER_TENANT);
        boolean hasUserId = StringUtils.hasText(userId);
        boolean hasTenantId = StringUtils.hasText(tenantId);
        if (!hasUserId && !hasTenantId) {
            logger.debug("请求缺少 X-User-Id/X-User-Tenant 头，跳过用户上下文提取");
            return;
        }
        UserContextHolder.set(new UserContext(hasUserId ? userId : null, hasTenantId ? tenantId : null));
    }
}
