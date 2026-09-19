package com.example.auth.web;

/**
 * 稳定的 API 错误体：{"error":"..."}。前端按 error 码做文案映射。
 */
public record ApiError(String error) {

    public static final String INVALID_CREDENTIALS = "invalid_credentials";

    public static final String ACCESS_DENIED = "access_denied";
}
