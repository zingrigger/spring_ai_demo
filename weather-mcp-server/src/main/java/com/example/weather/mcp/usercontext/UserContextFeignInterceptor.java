package com.example.weather.mcp.usercontext;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
        // 该拦截器对本模块所有 Feign 客户端生效（目前仅有 WeatherServiceClient）。
        if (StringUtils.hasText(context.userId())) {
            template.header(X_USER_ID, context.userId());
        }
        if (StringUtils.hasText(context.tenantId())) {
            template.header(X_USER_TENANT, context.tenantId());
        }
    }
}
