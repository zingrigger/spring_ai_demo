package com.example.weather.mcp.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the MCP server is an OAuth 2.1 resource server only: it
 * accepts access tokens issued by the external auth-server, requires the
 * {@code weather:read} scope, enforces the resource audience and no longer
 * hosts an authorization server of its own.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityTestConfiguration.class)
class McpSecurityFilterTest {

    private static final String ISSUER = "http://localhost:8083";
    private static final String RESOURCE = "http://localhost:8081/mcp";
    private static final String REQUIRED_SCOPE = "weather:read";
    private static final RSAKey SIGNING_KEY = generateRsaKey();

    private static final String INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";

    /**
     * Replaces the auth-server backed decoder with one that trusts a test key,
     * so the resource-server rules can be exercised without a running
     * auth-server. The validator stays the production one.
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void trustTheTestSigningKey() throws Exception {
        NimbusJwtDecoder delegate = NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
        delegate.setJwtValidator(McpJwtValidators.create(ISSUER, RESOURCE));
        given(this.jwtDecoder.decode(anyString()))
                .willAnswer((invocation) -> delegate.decode(invocation.getArgument(0)));
    }

    @Test
    void unauthorizedWhenNoToken() throws Exception {
        MvcResult result = mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andReturn();
        String challenge = result.getResponse().getHeader("WWW-Authenticate");
        assertThat(challenge)
                .contains("resource_metadata=\"http://localhost:8081/.well-known/oauth-protected-resource\"")
                .contains("scope=\"" + REQUIRED_SCOPE + "\"");
    }

    @Test
    @WithMockUser(authorities = "ROLE_OTHER")
    void forbiddenWithoutRequiredScope() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "SCOPE_weather:read")
    void allowedWithRequiredScope() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    @Test
    void acceptsTokenIssuedByTheAuthorizationServer() throws Exception {
        String token = accessToken(claims -> {
        });
        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    @Test
    void rejectsExpiredAccessToken() throws Exception {
        String token = accessToken(claims -> claims
                .issuedAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().minusSeconds(60)));

        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAccessTokenForAnotherResource() throws Exception {
        String token = accessToken(claims -> claims.audience(List.of("http://localhost:9999/mcp")));

        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAccessTokenFromAnotherIssuer() throws Exception {
        String token = accessToken(claims -> claims.issuer("http://evil.example.com"));

        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noLongerServesAnEmbeddedAuthorizationServer() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(csrf())
                        .param("grant_type", "client_credentials")
                        .param("scope", REQUIRED_SCOPE))
                .andExpect(status().isNotFound());
    }

    private static String accessToken(Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("auth-machine")
                .audience(List.of(RESOURCE))
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString())
                .claim("scope", REQUIRED_SCOPE);
        customizer.accept(claims);

        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(SIGNING_KEY)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static RSAKey generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("mcp-test-key")
                    .build();
        }
        catch (Exception exception) {
            throw new IllegalStateException("cannot generate the test RSA key", exception);
        }
    }
}
