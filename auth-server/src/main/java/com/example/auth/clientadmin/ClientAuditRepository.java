package com.example.auth.clientadmin;

import com.example.auth.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 审计写入：只记录变更字段名，不记录任何值。
 */
@Repository
public class ClientAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClientAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String registeredClientId, String clientId, AuditAction action, AuthenticatedUser actor,
                       List<String> changedFields) {
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_registered_client_audit
                    (registered_client_id, client_id, action, actor_user_id, actor_account, changed_fields)
                VALUES (?, ?, ?, ?, ?, ?)
                """, registeredClientId, clientId, action.name(), actor.id(), actor.account(),
                changedFields == null || changedFields.isEmpty() ? null : String.join(",", changedFields));
    }
}
