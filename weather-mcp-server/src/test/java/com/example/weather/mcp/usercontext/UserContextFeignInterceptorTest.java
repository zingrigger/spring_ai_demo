package com.example.weather.mcp.usercontext;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextFeignInterceptorTest {

    private final UserContextFeignInterceptor interceptor = new UserContextFeignInterceptor();

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void addsHeadersWhenContextPresent() {
        UserContextHolder.set(new UserContext("1001", "acme"));
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers().get("X-User-Id")).containsExactly("1001");
        assertThat(template.headers().get("X-User-Tenant")).containsExactly("acme");
    }

    @Test
    void skipsNullValues() {
        UserContextHolder.set(new UserContext("1001", null));
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers().get("X-User-Id")).containsExactly("1001");
        assertThat(template.headers()).doesNotContainKey("X-User-Tenant");
    }

    @Test
    void addsNoHeadersForAllNullContext() {
        UserContextHolder.set(new UserContext(null, null));
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKeys("X-User-Id", "X-User-Tenant");
    }

    @Test
    void skipsBlankUserId() {
        UserContextHolder.set(new UserContext(" ", "acme"));
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKey("X-User-Id");
        assertThat(template.headers().get("X-User-Tenant")).containsExactly("acme");
    }

    @Test
    void addsNoHeadersWithoutContext() {
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKeys("X-User-Id", "X-User-Tenant");
    }
}
