package com.example.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;

import com.example.auth.clientadmin.ClientAuditRepository;
import com.example.auth.clientadmin.ClientQueryRepository;
import com.example.auth.identity.IdentityRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@ActiveProfiles("test")
class AuthServerApplicationTest {

    // These beans need a JDBC DataSource, which this scaffold test deliberately
    // excludes; mock them so the context stays database-free.
    @MockitoBean
    private IdentityRepository identityRepository;

    @MockitoBean
    private RegisteredClientRepository registeredClientRepository;

    @MockitoBean
    private JdbcRegisteredClientRepository jdbcRegisteredClientRepository;

    @MockitoBean
    private OAuth2AuthorizationService authorizationService;

    @MockitoBean
    private OAuth2AuthorizationConsentService authorizationConsentService;

    @MockitoBean
    private ClientQueryRepository clientQueryRepository;

    @MockitoBean
    private ClientAuditRepository clientAuditRepository;

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${server.port}")
    private int serverPort;

    @Value("${auth.oauth.authorization-code-ttl}")
    private Duration authorizationCodeTtl;

    @Value("${auth.oauth.access-token-ttl}")
    private Duration accessTokenTtl;

    @Value("${auth.oauth.id-token-ttl}")
    private Duration idTokenTtl;

    @Value("${auth.oauth.refresh-token-ttl}")
    private Duration refreshTokenTtl;

    @Value("${auth.oauth.session-idle-timeout}")
    private Duration sessionIdleTimeout;

    @Test
    void startsWithConfiguredApplicationNameAndPort() {
        assertThat(applicationName).isEqualTo("auth-server");
        assertThat(serverPort).isEqualTo(8083);
    }

    @Test
    void exposesSpecTokenLifetimesAsDefaults() {
        assertThat(authorizationCodeTtl).isEqualTo(Duration.ofMinutes(5));
        assertThat(accessTokenTtl).isEqualTo(Duration.ofMinutes(10));
        assertThat(idTokenTtl).isEqualTo(Duration.ofMinutes(10));
        assertThat(refreshTokenTtl).isEqualTo(Duration.ofDays(30));
        assertThat(sessionIdleTimeout).isEqualTo(Duration.ofMinutes(30));
    }
}
