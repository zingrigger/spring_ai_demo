package com.example.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Loads the token signing key from an external PKCS#12/JKS keystore.
 *
 * <p>Startup fails when any part of the keystore configuration is missing or
 * unusable: the design forbids falling back to a generated key.
 */
@Configuration
public class KeyStoreConfig {

    @Bean
    JWKSource<SecurityContext> jwkSource(AuthServerProperties properties, ResourceLoader resourceLoader) {
        AuthServerProperties.Keystore keystore = properties.keystore();
        if (keystore == null) {
            throw new IllegalStateException("auth.keystore.* must configure the external token signing keystore");
        }
        requireText(keystore.location(), "auth.keystore.location");
        requireText(keystore.password(), "auth.keystore.password");
        requireText(keystore.type(), "auth.keystore.type");
        requireText(keystore.alias(), "auth.keystore.alias");

        char[] password = keystore.password().toCharArray();
        KeyStore store = loadKeyStore(keystore, password, resourceLoader);
        Key key = loadPrivateKey(store, keystore.alias(), password);
        if (!(key instanceof RSAPrivateKey privateKey)) {
            throw new IllegalStateException("auth.keystore.alias=" + keystore.alias() + " must hold an RSA private key");
        }

        Certificate certificate = certificate(store, keystore.alias());
        RSAKey rsaKey = buildRsaKey((RSAPublicKey) certificate.getPublicKey(), privateKey);
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    private static void requireText(String value, String property) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " must be configured with the external signing keystore");
        }
    }

    private static KeyStore loadKeyStore(AuthServerProperties.Keystore keystore, char[] password,
                                         ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(keystore.location());
        try (InputStream input = resource.getInputStream()) {
            KeyStore store = KeyStore.getInstance(keystore.type());
            store.load(input, password);
            return store;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read auth.keystore.location=" + keystore.location()
                    + " (check the path, type and password)", ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unsupported auth.keystore.type=" + keystore.type(), ex);
        }
    }

    private static Key loadPrivateKey(KeyStore store, String alias, char[] password) {
        try {
            Key key = store.getKey(alias, password);
            if (key == null) {
                throw new IllegalStateException("auth.keystore.alias=" + alias
                        + " does not exist in the configured keystore");
            }
            return key;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to unlock auth.keystore.alias=" + alias
                    + " (check the key password)", ex);
        }
    }

    private static Certificate certificate(KeyStore store, String alias) {
        try {
            Certificate certificate = store.getCertificate(alias);
            if (certificate == null || !(certificate.getPublicKey() instanceof RSAPublicKey)) {
                throw new IllegalStateException("auth.keystore.alias=" + alias
                        + " must have a certificate with an RSA public key");
            }
            return certificate;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to read auth.keystore.alias=" + alias, ex);
        }
    }

    private static RSAKey buildRsaKey(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Unable to derive the token signing key id", ex);
        }
    }
}
