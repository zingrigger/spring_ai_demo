-- Local development clients. Production clients are added by operations SQL and
-- their plaintext secrets are injected at deploy time, never committed here.
--
-- auth-web-public : public browser/SPA client, Authorization Code + PKCE, refresh tokens.
-- auth-machine    : confidential machine client, Client Credentials only.
--
-- client_settings / token_settings are the exact Jackson documents written by
-- JdbcRegisteredClientRepository (spring-security-oauth2-authorization-server 7.0.7).

INSERT INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at, client_name,
    client_authentication_methods, authorization_grant_types, redirect_uris,
    post_logout_redirect_uris, scopes, client_settings, token_settings)
VALUES
    ('6f0d1d0e-3a4a-4c1f-9d02-9f1d0f5a1a01',
     'auth-web-public',
     CURRENT_TIMESTAMP,
     NULL,
     NULL,
     'Auth Web Client',
     'none',
     'refresh_token,authorization_code',
     'http://127.0.0.1:8080/login/oauth2/code/auth-server,http://localhost:8080/login/oauth2/code/auth-server',
     NULL,
     'openid,profile,weather:read',
     '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true,"settings.client.require-authorization-consent":true}',
     '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.reuse-refresh-tokens":false,"settings.token.x509-certificate-bound-access-tokens":false,"settings.token.id-token-signature-algorithm":["org.springframework.security.oauth2.jose.jws.SignatureAlgorithm","RS256"],"settings.token.access-token-time-to-live":["java.time.Duration","PT10M"],"settings.token.access-token-format":{"@class":"org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat","value":"self-contained"},"settings.token.refresh-token-time-to-live":["java.time.Duration","PT720H"],"settings.token.authorization-code-time-to-live":["java.time.Duration","PT5M"],"settings.token.device-code-time-to-live":["java.time.Duration","PT5M"]}'),
    ('6f0d1d0e-3a4a-4c1f-9d02-9f1d0f5a1a02',
     'auth-machine',
     CURRENT_TIMESTAMP,
     '{bcrypt}$2a$10$jxFYnnVPvxjN4n5Hexeh1.z7KriAdHUHshxflFKs66ekNijNYGS1K',
     NULL,
     'Auth Machine Client',
     'client_secret_basic',
     'client_credentials',
     NULL,
     NULL,
     'weather:read',
     '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true,"settings.client.require-authorization-consent":false}',
     '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.reuse-refresh-tokens":true,"settings.token.x509-certificate-bound-access-tokens":false,"settings.token.id-token-signature-algorithm":["org.springframework.security.oauth2.jose.jws.SignatureAlgorithm","RS256"],"settings.token.access-token-time-to-live":["java.time.Duration","PT10M"],"settings.token.access-token-format":{"@class":"org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat","value":"self-contained"},"settings.token.refresh-token-time-to-live":["java.time.Duration","PT1H"],"settings.token.authorization-code-time-to-live":["java.time.Duration","PT5M"],"settings.token.device-code-time-to-live":["java.time.Duration","PT5M"]}');
