package com.example.auth.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design requires startup to fail when the external keystore is missing or
 * unusable; there is no random-key fallback.
 */
class KeyStoreConfigTest {

    private static final String KEYSTORE = "classpath:keystore/auth-server-test.p12";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KeyStoreConfig.class);

    @Test
    void failsFastWhenKeystoreLocationIsMissing() {
        runner.withBean(AuthServerProperties.class, () -> properties("", "changeit", "PKCS12", "auth-server-test"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("auth.keystore.location");
                });
    }

    @Test
    void failsFastWhenKeystorePasswordIsWrong() {
        runner.withBean(AuthServerProperties.class, () -> properties(KEYSTORE, "wrong-password", "PKCS12", "auth-server-test"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("auth.keystore.location");
                });
    }

    @Test
    void failsFastWhenAliasDoesNotExist() {
        runner.withBean(AuthServerProperties.class, () -> properties(KEYSTORE, "changeit", "PKCS12", "unknown-alias"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("auth.keystore.alias");
                });
    }

    @Test
    void loadsTheConfiguredRsaKey() {
        runner.withBean(AuthServerProperties.class, () -> properties(KEYSTORE, "changeit", "PKCS12", "auth-server-test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JWKSource.class);
                });
    }

    private static AuthServerProperties properties(String location, String password, String type, String alias) {
        return new AuthServerProperties("http://localhost:8083",
                new AuthServerProperties.Keystore(location, password, type, alias), null, null, null);
    }
}
