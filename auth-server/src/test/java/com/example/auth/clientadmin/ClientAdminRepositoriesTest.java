package com.example.auth.clientadmin;

import com.example.auth.client.ClientManagementSettings;
import com.example.auth.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理面仓储：列表读模型、审计写入、禁用过滤与清理语义。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ClientAdminRepositoriesTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private JdbcRegisteredClientRepository rawRepository;

    @Autowired
    private RegisteredClientRepository decoratedRepository;

    @Autowired
    private ClientQueryRepository clientQueryRepository;

    @Autowired
    private ClientAuditRepository clientAuditRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void summariesCarryTypeEnabledStateAndAuditMetadata() {
        RegisteredClient client = machineClient("reporting-service");
        this.rawRepository.save(client);
        this.clientAuditRepository.record(client.getId(), client.getClientId(), AuditAction.CREATE,
                new AuthenticatedUser(1L, "alice", "Alice"), List.of());

        List<ClientSummaryView> summaries = this.clientQueryRepository.findAll("report");

        assertThat(summaries).hasSize(1);
        ClientSummaryView summary = summaries.get(0);
        assertThat(summary.clientId()).isEqualTo("reporting-service");
        assertThat(summary.type()).isEqualTo("machine");
        assertThat(summary.enabled()).isTrue();
        assertThat(summary.updatedBy()).isEqualTo("alice");
        assertThat(summary.updatedAt()).isNotNull();
    }

    @Test
    void disabledClientsAreHiddenFromTheDecoratedRepository() {
        RegisteredClient client = ClientManagementSettings.withEnabled(machineClient("disabled-client"), false);
        this.rawRepository.save(client);

        assertThat(this.rawRepository.findByClientId("disabled-client")).isNotNull();
        assertThat(this.decoratedRepository.findByClientId("disabled-client")).isNull();
        assertThat(this.decoratedRepository.findById(client.getId())).isNull();
    }

    @Test
    void cleanupRemovesAuthorizationsAndConsents() {
        RegisteredClient client = machineClient("cleanup-client");
        this.rawRepository.save(client);
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID().toString(), client.getId(), "cleanup-client", "client_credentials");
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                VALUES (?, ?, ?)
                """, client.getId(), "1", "openid");

        this.clientQueryRepository.deleteAuthorizationsAndConsents(client.getId());

        assertThat(count("oauth2_authorization", client.getId())).isZero();
        assertThat(count("oauth2_authorization_consent", client.getId())).isZero();
        assertThat(this.clientQueryRepository.deleteRegisteredClient(client.getId())).isEqualTo(1);
        assertThat(this.rawRepository.findByClientId("cleanup-client")).isNull();
    }

    @Test
    void clientTypeResolverMatchesThePresets() {
        assertThat(ClientTypeResolver.resolve(List.of("authorization_code", "refresh_token"),
                List.of("client_secret_basic"))).isEqualTo("web");
        assertThat(ClientTypeResolver.resolve(List.of("client_credentials"),
                List.of("client_secret_basic"))).isEqualTo("machine");
        assertThat(ClientTypeResolver.resolve(List.of("authorization_code"), List.of("none"))).isEqualTo("public");
        assertThat(ClientTypeResolver.resolve(List.of("urn:example:grant"),
                List.of("client_secret_basic"))).isEqualTo("custom");
    }

    private static RegisteredClient machineClient(String clientId) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientIdIssuedAt(Instant.now())
                .clientSecret("{bcrypt}$2a$10$" + UUID.randomUUID().toString().replace("-", ""))
                .clientName(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("weather:read")
                .build();
    }

    private int count(String table, String registeredClientId) {
        Integer count = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE registered_client_id = ?",
                Integer.class, registeredClientId);
        return count == null ? 0 : count;
    }
}
