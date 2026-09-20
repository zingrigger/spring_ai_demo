package com.example.auth.clientadmin;

/**
 * 审计动作；写入 {@code oauth2_registered_client_audit.action}。
 */
public enum AuditAction {
    CREATE, UPDATE, ROTATE_SECRET, ENABLE, DISABLE, DELETE
}
