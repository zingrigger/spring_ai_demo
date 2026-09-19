-- Local development client for MCP clients that run the Authorization Code +
-- PKCE flow against the weather MCP resource (for example MCP Inspector).
--
-- weather-mcp-inspector : public client (no secret), Authorization Code + PKCE
--                         only. Spring Authorization Server only issues refresh
--                         tokens to authenticated clients, so this client
--                         re-authorizes instead of refreshing.
--                         Scopes: weather:read, audience-bound to the MCP
--                         resource via ResourceAudienceTokenCustomizer.

INSERT INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris,
    post_logout_redirect_uris, scopes, client_settings, token_settings)
VALUES
    ('6f0d1d0e-3a4a-4c1f-9d02-9f1d0f5a1a03',
     'weather-mcp-inspector',
     CURRENT_TIMESTAMP,
     NULL,
     NULL,
     'Weather MCP Inspector',
     'none',
     'authorization_code',
     'http://127.0.0.1:6274/oauth/callback,http://localhost:6274/oauth/callback,http://127.0.0.1:5173/callback',
     NULL,
     'weather:read',
     '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true,"settings.client.require-authorization-consent":true}',
     '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.reuse-refresh-tokens":false,"settings.token.x509-certificate-bound-access-tokens":false,"settings.token.id-token-signature-algorithm":["org.springframework.security.oauth2.jose.jws.SignatureAlgorithm","RS256"],"settings.token.access-token-time-to-live":["java.time.Duration","PT10M"],"settings.token.access-token-format":{"@class":"org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat","value":"self-contained"},"settings.token.refresh-token-time-to-live":["java.time.Duration","PT1H"],"settings.token.authorization-code-time-to-live":["java.time.Duration","PT5M"],"settings.token.device-code-time-to-live":["java.time.Duration","PT5M"]}');
