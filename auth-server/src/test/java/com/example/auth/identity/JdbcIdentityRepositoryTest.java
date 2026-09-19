package com.example.auth.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import(JdbcIdentityRepository.class)
@Sql("/db/sys-identity-fixtures.sql")
@Testcontainers
class JdbcIdentityRepositoryTest {

    private static final String ALICE_PASSWORD_HASH =
            "$2a$10$GjbGmfS63PiP9AquNwCWguef/LOJ0l/fxCbpB9SDXM.XO40cPaCxK";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private IdentityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void readsStoredBcryptPasswordVerbatim() {
        UserAccount alice = repository.findByAccount("alice").orElseThrow();

        assertThat(alice.id()).isEqualTo(1L);
        assertThat(alice.account()).isEqualTo("alice");
        assertThat(alice.name()).isEqualTo("Alice");
        assertThat(alice.password()).isEqualTo(ALICE_PASSWORD_HASH);
    }

    @Test
    void returnsEmptyForUnknownAccount() {
        assertThat(repository.findByAccount("nobody")).isEmpty();
    }

    @Test
    void returnsOnlyOrganizationsLinkedToTheUser() {
        assertThat(repository.findOrganizations(1L))
                .containsExactly(new Organization(10L, "Alpha"), new Organization(20L, "Beta"));
        assertThat(repository.findOrganizations(2L))
                .containsExactly(new Organization(20L, "Beta"));
        assertThat(repository.findOrganizations(99L)).isEmpty();
    }

    @Test
    void returnsRolesOnlyForTheSelectedOrganization() {
        List<Role> alphaRoles = repository.findRoles(1L, 10L);
        List<Role> betaRoles = repository.findRoles(1L, 20L);

        assertThat(alphaRoles).containsExactly(new Role(100L, "ADMIN"), new Role(101L, "VIEWER"));
        assertThat(betaRoles).containsExactly(new Role(102L, "AUDITOR"));
    }

    @Test
    void neverReturnsRolesFromAnotherUsersOrganization() {
        assertThat(repository.findRoles(2L, 10L)).isEmpty();
        assertThat(repository.findRoles(1L, 999L)).isEmpty();
    }

    @Test
    void appliesFlywaySchemaAndSeedsLocalClients() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth2_registered_client", Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client "
                        + "WHERE client_id IN ('auth-web-public', 'auth-machine', 'weather-mcp-inspector')",
                Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth2_authorization", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oauth2_authorization_consent", Integer.class)).isZero();
    }
}
