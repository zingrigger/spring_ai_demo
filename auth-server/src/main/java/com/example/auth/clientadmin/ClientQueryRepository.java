package com.example.auth.clientadmin;

import com.example.auth.client.ClientManagementSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 框架的 RegisteredClientRepository 没有 list/delete，这里补查询与清理；
 * SELECT 列表中不出现 client_secret 列。
 */
@Repository
public class ClientQueryRepository {

    private static final String FIND_ALL_SQL = """
            SELECT c.id, c.client_id, c.client_name, c.client_authentication_methods,
                   c.authorization_grant_types, c.scopes, c.client_settings,
                   a.created_at AS updated_at, a.actor_account AS updated_by
            FROM oauth2_registered_client c
            LEFT JOIN oauth2_registered_client_audit a
                   ON a.id = (SELECT MAX(a2.id) FROM oauth2_registered_client_audit a2 WHERE a2.client_id = c.client_id)
            """;

    private static final String ORDER_BY = " ORDER BY c.client_name, c.client_id";

    private final JdbcTemplate jdbcTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final RowMapper<ClientSummaryView> summaryMapper = (rs, rowNum) -> toSummary(rs);

    public ClientQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ClientSummaryView> findAll(String query) {
        if (query == null || query.isBlank()) {
            return this.jdbcTemplate.query(FIND_ALL_SQL + ORDER_BY, this.summaryMapper);
        }
        String like = "%" + query.trim() + "%";
        return this.jdbcTemplate.query(
                FIND_ALL_SQL + " WHERE c.client_id LIKE ? OR c.client_name LIKE ?" + ORDER_BY,
                this.summaryMapper, like, like);
    }

    public void deleteAuthorizationsAndConsents(String registeredClientId) {
        this.jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?",
                registeredClientId);
        this.jdbcTemplate.update("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?",
                registeredClientId);
    }

    public int deleteRegisteredClient(String registeredClientId) {
        return this.jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", registeredClientId);
    }

    private ClientSummaryView toSummary(ResultSet rs) throws SQLException {
        List<String> grantTypes = split(rs.getString("authorization_grant_types"));
        List<String> authenticationMethods = split(rs.getString("client_authentication_methods"));
        return new ClientSummaryView(
                rs.getString("client_id"),
                rs.getString("client_name"),
                ClientTypeResolver.resolve(grantTypes, authenticationMethods),
                grantTypes,
                split(rs.getString("scopes")),
                isEnabled(rs.getString("client_settings")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getString("updated_by"));
    }

    private boolean isEnabled(String clientSettingsJson) {
        if (clientSettingsJson == null || clientSettingsJson.isBlank()) {
            return true;
        }
        try {
            Object enabled = this.jsonMapper.readValue(clientSettingsJson, java.util.Map.class)
                    .get(ClientManagementSettings.ENABLED);
            return !Boolean.FALSE.equals(enabled);
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("Stored client_settings is not valid JSON", ex);
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter((part) -> !part.isEmpty()).toList();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
