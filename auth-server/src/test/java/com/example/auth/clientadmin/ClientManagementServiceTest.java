package com.example.auth.clientadmin;

import com.example.auth.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 创建/读取服务的校验、哈希与审计行为。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ClientManagementServiceTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final AuthenticatedUser ALICE = new AuthenticatedUser(1L, "alice", "Alice");

    @Autowired
    private ClientManagementService clientManagementService;

    @Autowired
    private JdbcRegisteredClientRepository rawRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createStoresAnEncodedSecretAndReturnsThePlaintextOnce() {
        ClientManagementService.CreatedClient created = this.clientManagementService.create(
                new CreateClientCommand("reporting-service", "Reporting Service", "machine",
                        List.of(), List.of(), List.of("weather:read"), null), ALICE);

        assertThat(created.clientSecret()).isNotBlank();
        assertThat(created.client().type()).isEqualTo("machine");
        assertThat(created.client().enabled()).isTrue();

        RegisteredClient stored = this.rawRepository.findByClientId("reporting-service");
        assertThat(stored).isNotNull();
        assertThat(stored.getClientSecret()).startsWith("{bcrypt}$2a$").doesNotContain(created.clientSecret());
        assertThat(auditCount("reporting-service", "CREATE")).isEqualTo(1);
    }

    @Test
    void createRejectsDuplicateClientIds() {
        this.clientManagementService.create(machineCommand("duplicate-client"), ALICE);

        assertThatThrownBy(() -> this.clientManagementService.create(machineCommand("duplicate-client"), ALICE))
                .isInstanceOfSatisfying(ClientManagementException.class,
                        (ex) -> assertThat(ex.error()).isEqualTo("client_id_taken"));
    }

    @Test
    void createRejectsNonHttpsRedirectUris() {
        CreateClientCommand command = new CreateClientCommand("bad-uri-client", "Bad", "web",
                List.of("http://example.com/callback"), List.of(), List.of("openid"), null);

        assertThatThrownBy(() -> this.clientManagementService.create(command, ALICE))
                .isInstanceOfSatisfying(ClientManagementException.class, (ex) -> {
                    assertThat(ex.error()).isEqualTo("invalid_client_metadata");
                    assertThat(ex.details())
                            .containsExactly(new ClientManagementException.Detail("redirectUris", "invalid_uri"));
                });
    }

    @Test
    void publicClientsHaveNoSecretAndRequirePkce() {
        ClientManagementService.CreatedClient created = this.clientManagementService.create(
                new CreateClientCommand("spa-client", "SPA", "public",
                        List.of("http://localhost:5173/callback"), List.of(), List.of("openid"), null), ALICE);

        assertThat(created.clientSecret()).isNull();
        assertThat(created.client().requireProofKey()).isTrue();
        assertThat(created.client().type()).isEqualTo("public");
    }

    @Test
    void getAndListReturnStoredClients() {
        this.clientManagementService.create(machineCommand("listable-client"), ALICE);

        assertThat(this.clientManagementService.get("listable-client").clientId()).isEqualTo("listable-client");
        assertThat(this.clientManagementService.list("listable")).hasSize(1);

        assertThatThrownBy(() -> this.clientManagementService.get("missing-client"))
                .isInstanceOfSatisfying(ClientManagementException.class,
                        (ex) -> assertThat(ex.error()).isEqualTo("client_not_found"));
    }

    @Test
    void updateChangesFieldsAndPreservesTheSecret() {
        this.clientManagementService.create(machineCommand("updatable-client"), ALICE);
        String secretHash = this.rawRepository.findByClientId("updatable-client").getClientSecret();

        ClientDetailView updated = this.clientManagementService.update("updatable-client",
                new UpdateClientCommand("Renamed Client", null, null, List.of("weather:read", "profile"),
                        null, null, null, null), ALICE);

        assertThat(updated.clientName()).isEqualTo("Renamed Client");
        assertThat(updated.scopes()).containsExactly("profile", "weather:read");
        assertThat(this.rawRepository.findByClientId("updatable-client").getClientSecret()).isEqualTo(secretHash);
        assertThat(auditCount("updatable-client", "UPDATE")).isEqualTo(1);
    }

    @Test
    void updateRejectsPublicClientsWithClientCredentials() {
        assertThatThrownBy(() -> this.clientManagementService.update("auth-machine",
                new UpdateClientCommand(null, null, null, null, List.of("client_credentials"),
                        List.of("none"), null, null), ALICE))
                .isInstanceOfSatisfying(ClientManagementException.class, (ex) -> {
                    assertThat(ex.error()).isEqualTo("invalid_client_metadata");
                    assertThat(ex.details()).containsExactly(new ClientManagementException.Detail(
                            "clientAuthenticationMethods", "public_client_requires_authorization_code"));
                });
    }

    @Test
    void updateCanDisablePkceForConfidentialClients() {
        this.clientManagementService.create(new CreateClientCommand("pkce-client", "PKCE Client", "web",
                List.of("https://example.com/callback"), List.of(), List.of("openid"), null), ALICE);
        assertThat(this.clientManagementService.get("pkce-client").requireProofKey()).isTrue();

        ClientDetailView updated = this.clientManagementService.update("pkce-client",
                new UpdateClientCommand(null, null, null, null, null, null, null, false), ALICE);

        assertThat(updated.requireProofKey()).isFalse();
        assertThat(this.rawRepository.findByClientId("pkce-client").getClientSettings().isRequireProofKey())
                .isFalse();
        assertThat(auditCount("pkce-client", "UPDATE")).isEqualTo(1);
    }

    @Test
    void updateRejectsDisablingPkceForPublicClients() {
        this.clientManagementService.create(new CreateClientCommand("public-pkce-client", "Public PKCE", "public",
                List.of("https://example.com/callback"), List.of(), List.of("openid"), null), ALICE);

        assertThatThrownBy(() -> this.clientManagementService.update("public-pkce-client",
                new UpdateClientCommand(null, null, null, null, null, null, null, false), ALICE))
                .isInstanceOfSatisfying(ClientManagementException.class, (ex) -> {
                    assertThat(ex.error()).isEqualTo("invalid_client_metadata");
                    assertThat(ex.details()).containsExactly(new ClientManagementException.Detail(
                            "requireProofKey", "public_client_requires_pkce"));
                });
    }

    @Test
    void rotateSecretReplacesTheStoredHash() {
        this.clientManagementService.create(machineCommand("rotating-client"), ALICE);
        String before = this.rawRepository.findByClientId("rotating-client").getClientSecret();

        String newSecret = this.clientManagementService.rotateSecret("rotating-client", ALICE);
        String after = this.rawRepository.findByClientId("rotating-client").getClientSecret();

        assertThat(after).isNotEqualTo(before).startsWith("{bcrypt}$2a$").doesNotContain(newSecret);
        assertThat(auditCount("rotating-client", "ROTATE_SECRET")).isEqualTo(1);
    }

    @Test
    void disablingCleansUpAuthorizationsAndConsentsAndStaysReversible() {
        this.clientManagementService.create(machineCommand("disabling-client"), ALICE);
        RegisteredClient stored = this.rawRepository.findByClientId("disabling-client");
        this.jdbcTemplate.update("""
                INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                VALUES (?, ?, ?)
                """, stored.getId(), "1", "openid");

        ClientDetailView disabled = this.clientManagementService.setEnabled("disabling-client", false, ALICE);

        assertThat(disabled.enabled()).isFalse();
        assertThat(this.clientManagementService.get("disabling-client").enabled()).isFalse();
        assertThat(this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization_consent WHERE registered_client_id = ?",
                Integer.class, stored.getId())).isZero();
        assertThat(auditCount("disabling-client", "DISABLE")).isEqualTo(1);

        ClientDetailView enabled = this.clientManagementService.setEnabled("disabling-client", true, ALICE);
        assertThat(enabled.enabled()).isTrue();
    }

    @Test
    void deleteRemovesTheClientButKeepsTheAudit() {
        this.clientManagementService.create(machineCommand("deletable-client"), ALICE);

        this.clientManagementService.delete("deletable-client", ALICE);

        assertThat(this.rawRepository.findByClientId("deletable-client")).isNull();
        assertThat(auditCount("deletable-client", "CREATE")).isEqualTo(1);
        assertThat(auditCount("deletable-client", "DELETE")).isEqualTo(1);
    }

    private static CreateClientCommand machineCommand(String clientId) {
        return new CreateClientCommand(clientId, clientId, "machine", List.of(), List.of(),
                List.of("weather:read"), null);
    }

    private int auditCount(String clientId, String action) {
        Integer count = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_registered_client_audit WHERE client_id = ? AND action = ?
                """, Integer.class, clientId, action);
        return count == null ? 0 : count;
    }
}
