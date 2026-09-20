package com.example.auth.clientadmin;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 管理面业务错误：error 是稳定错误码，details 是字段级提示（可为空）。
 */
public class ClientManagementException extends RuntimeException {

    private final HttpStatus status;

    private final String error;

    private final List<Detail> details;

    public ClientManagementException(HttpStatus status, String error, List<Detail> details) {
        super(error);
        this.status = status;
        this.error = error;
        this.details = List.copyOf(details);
    }

    public HttpStatus status() {
        return this.status;
    }

    public String error() {
        return this.error;
    }

    public List<Detail> details() {
        return this.details;
    }

    public record Detail(String field, String code) {
    }
}
