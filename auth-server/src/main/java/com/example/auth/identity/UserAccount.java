package com.example.auth.identity;

/**
 * Read-only view of a row in {@code sys_user}. {@code password} is the stored
 * BCrypt hash and is never rewritten by auth-server.
 */
public record UserAccount(long id, String account, String name, String password) {
}
