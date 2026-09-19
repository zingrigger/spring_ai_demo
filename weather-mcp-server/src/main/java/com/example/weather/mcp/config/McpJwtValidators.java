package com.example.weather.mcp.config;

import java.util.List;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Token validation rules for this protected resource: the default timestamp
 * checks plus issuer and audience (RFC 9068).
 */
public final class McpJwtValidators {

    static final String INVALID_TOKEN = "invalid_token";

    private McpJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> create(String issuer, String requiredAudience) {
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = (token) -> hasAudience(token, requiredAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(INVALID_TOKEN,
                        "The required audience \"" + requiredAudience + "\" is missing", null));
        return new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator);
    }

    private static boolean hasAudience(Jwt token, String requiredAudience) {
        List<String> audiences = token.getAudience();
        return audiences != null && audiences.contains(requiredAudience);
    }
}
