package com.example.auth;

import com.example.auth.oauth.AuthorizationCodeFlow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack verification on MySQL 8: Flyway schema, the read-only identity
 * fixtures and the persistence the OAuth flows leave behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class AuthServerMySqlIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesTheThreeOAuthTables() {
        Integer tables = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('oauth2_registered_client', 'oauth2_authorization', 'oauth2_authorization_consent')
                """, Integer.class);
        assertThat(tables).isEqualTo(3);

        Integer migrations = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IN ('1', '2', '3') AND success = 1
                """, Integer.class);
        assertThat(migrations).isEqualTo(3);
    }

    @Test
    void clientCredentialsAuthorizationIsPersisted() throws Exception {
        this.mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("auth-machine", "auth-machine-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "weather:read"))
                .andExpect(status().isOk());

        Integer authorizations = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_authorization
                WHERE authorization_grant_type = 'client_credentials' AND principal_name = 'auth-machine'
                """, Integer.class);
        assertThat(authorizations).isEqualTo(1);

        String accessToken = this.jdbcTemplate.queryForObject("""
                SELECT access_token_value FROM oauth2_authorization
                WHERE authorization_grant_type = 'client_credentials' AND principal_name = 'auth-machine'
                """, String.class);
        assertThat(accessToken).isNotBlank();
    }

    @Test
    void authorizationCodeFlowPersistsAuthorizationAndConsent() throws Exception {
        AuthorizationCodeFlow flow = new AuthorizationCodeFlow(this.mockMvc);
        flow.authorizeAndExchangeTokens("alice", "alice-password", 10L, "openid profile");

        Integer authorizations = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_authorization
                WHERE authorization_grant_type = 'authorization_code' AND principal_name = '1'
                """, Integer.class);
        assertThat(authorizations).isEqualTo(1);

        String refreshToken = this.jdbcTemplate.queryForObject("""
                SELECT refresh_token_value FROM oauth2_authorization
                WHERE authorization_grant_type = 'authorization_code' AND principal_name = '1'
                """, String.class);
        assertThat(refreshToken).isNotBlank();

        Integer consents = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_authorization_consent consent
                JOIN oauth2_registered_client client ON client.id = consent.registered_client_id
                WHERE client.client_id = 'auth-web-public' AND consent.principal_name = '1'
                """, Integer.class);
        assertThat(consents).isEqualTo(1);
    }

    @Test
    void clientSecretsAreStoredEncoded() {
        List<String> secrets = this.jdbcTemplate.queryForList(
                "SELECT client_secret FROM oauth2_registered_client WHERE client_secret IS NOT NULL",
                String.class);

        assertThat(secrets).isNotEmpty().allSatisfy(
                (secret) -> assertThat(secret).startsWith("{bcrypt}$2a$"));
    }
}
