package com.example.weather.mcp.usercontext;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;

@Component
public final class UserContextFeignInterceptor implements RequestInterceptor {

    private static final String X_USER_ID = "X-User-Id";
    private static final String X_USER_TENANT = "X-User-Tenant";

    @Override
    public void apply(RequestTemplate template) {
        UserContext context = UserContextHolder.get();
        if (context == null) {
            return;
        }
        if (context.userId() != null) {
            template.header(X_USER_ID, context.userId());
        }
        if (context.tenantId() != null) {
            template.header(X_USER_TENANT, context.tenantId());
        }
    }
}
