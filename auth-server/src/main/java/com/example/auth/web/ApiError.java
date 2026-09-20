package com.example.auth.web;

import java.util.List;

/**
 * 稳定的 API 错误体：{"error":"...","details":[...]}。前端按 error 码做文案映射。
 */
public record ApiError(String error, List<Detail> details) {

    public static final String INVALID_CREDENTIALS = "invalid_credentials";

    public static final String ACCESS_DENIED = "access_denied";

    public ApiError(String error) {
        this(error, null);
    }

    public record Detail(String field, String code) {
    }
}
