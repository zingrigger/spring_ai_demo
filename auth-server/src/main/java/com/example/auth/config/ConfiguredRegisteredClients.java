package com.example.auth.config;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * Applies the configured token lifetimes to every registered client.
 *
 * <p>The clients themselves are stored in the Flyway-managed schema, but the
 * lifetimes are deployment inputs ({@code auth.oauth.*}), so they are applied
 * when a client is read. Refresh tokens always rotate.
 */
public class ConfiguredRegisteredClients implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final AuthServerProperties properties;

    public ConfiguredRegisteredClients(RegisteredClientRepository delegate, AuthServerProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        this.delegate.save(registeredClient);
    }

    @Override
    public RegisteredClient findById(String id) {
        return withConfiguredLifetimes(this.delegate.findById(id));
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return withConfiguredLifetimes(this.delegate.findByClientId(clientId));
    }

    private RegisteredClient withConfiguredLifetimes(RegisteredClient registeredClient) {
        if (registeredClient == null) {
            return null;
        }
        AuthServerProperties.Oauth configured = this.properties.oauth();
        TokenSettings existing = registeredClient.getTokenSettings();
        TokenSettings tokenSettings = TokenSettings.builder()
                .authorizationCodeTimeToLive(configured.authorizationCodeTtl())
                .accessTokenTimeToLive(configured.accessTokenTtl())
                .refreshTokenTimeToLive(configured.refreshTokenTtl())
                .deviceCodeTimeToLive(existing.getDeviceCodeTimeToLive())
                .reuseRefreshTokens(false)
                .accessTokenFormat(existing.getAccessTokenFormat())
                .idTokenSignatureAlgorithm(existing.getIdTokenSignatureAlgorithm())
                .x509CertificateBoundAccessTokens(existing.isX509CertificateBoundAccessTokens())
                .build();
        return RegisteredClient.from(registeredClient).tokenSettings(tokenSettings).build();
    }
}
