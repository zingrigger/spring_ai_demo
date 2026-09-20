package com.example.auth.security;

/**
 * 平台管理员的授权标记；账号来源见 {@code auth.admin.accounts}。
 */
public final class PlatformAdmin {

    public static final String AUTHORITY = "ROLE_PLATFORM_ADMIN";

    private PlatformAdmin() {
    }
}
