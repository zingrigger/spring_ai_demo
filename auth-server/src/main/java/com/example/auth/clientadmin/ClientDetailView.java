package com.example.auth.clientadmin;

import java.time.Instant;
import java.util.List;

/**
 * 详情读模型；tokenSettings 展示的是经过部署配置覆盖后的生效值。
 */
public record ClientDetailView(String clientId, String clientName, String type,
                               List<String> clientAuthenticationMethods, List<String> grantTypes,
                               List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> scopes,
                               boolean requireProofKey, boolean requireAuthorizationConsent,
                               Instant clientIdIssuedAt, Instant clientSecretExpiresAt, boolean enabled,
                               TokenSettingsView tokenSettings) {

    public record TokenSettingsView(String accessTokenTimeToLive, String refreshTokenTimeToLive,
                                    String authorizationCodeTimeToLive, String idTokenSignatureAlgorithm,
                                    String accessTokenFormat) {
    }
}
