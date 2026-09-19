package com.example.auth.config;

import com.example.auth.oidc.UserInfoService;
import com.example.auth.security.OrganizationBindingFilter;
import com.example.auth.token.AuthorizationTokenCustomizer;
import com.example.auth.token.TokenClaimsCustomizer;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Spring Authorization Server standard endpoints backed by the Flyway-managed
 * JDBC schema, plus the issuer, token generator and OIDC support.
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http, AuthorizationServerSettings authorizationServerSettings,
            RequestCache requestCache, SecurityContextRepository securityContextRepository,
            UserInfoService userInfoService) throws Exception {
        http.addFilterAfter(new OrganizationBindingFilter(authorizationServerSettings, requestCache),
                SecurityContextHolderFilter.class);
        http.securityContext((securityContext) -> securityContext
                .securityContextRepository(securityContextRepository));
        http.oauth2AuthorizationServer((authorizationServer) -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc((oidc) -> oidc.userInfoEndpoint(
                    (userInfo) -> userInfo.userInfoMapper(userInfoService::load)));
        });
        http.authorizeHttpRequests((authorize) -> authorize.anyRequest().authenticated());
        http.oauth2ResourceServer((resourceServer) -> resourceServer.jwt(Customizer.withDefaults()));
        http.exceptionHandling((exceptions) -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"), textHtmlRequests()));
        return http.build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate,
                                                          AuthServerProperties properties) {
        return new ConfiguredRegisteredClients(new JdbcRegisteredClientRepository(jdbcTemplate), properties);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                    RegisteredClientRepository registeredClientRepository) {
        JdbcOAuth2AuthorizationService authorizationService =
                new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        authorizationService.setAuthorizationRowMapper(
                new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(
                        registeredClientRepository, authorizationJsonMapper()));
        return authorizationService;
    }

    /**
     * The JDBC authorization service serializes the authorization attributes, including
     * the authenticated principal and its organization binding. Spring Security's default
     * polymorphic type validator rejects application types, so this mapper allows exactly
     * the auth-server security package on top of the framework's own allow list.
     */
    private static JsonMapper authorizationJsonMapper() {
        BasicPolymorphicTypeValidator.Builder validator =
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.example.auth.security.")
                        .allowIfSubType(ArrayList.class);
        List<JacksonModule> modules = SecurityJacksonModules.getModules(
                AuthorizationServerConfig.class.getClassLoader(), validator);
        return JsonMapper.builder().addModules(modules).build();
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthServerProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(JwtEncoder jwtEncoder,
                                           TokenClaimsCustomizer tokenClaimsCustomizer,
                                           AuthorizationTokenCustomizer authorizationTokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer((context) -> {
            tokenClaimsCustomizer.customize(context);
            authorizationTokenCustomizer.customize(context);
        });
        OAuth2AccessTokenGenerator accessTokenGenerator = new OAuth2AccessTokenGenerator();
        OAuth2RefreshTokenGenerator refreshTokenGenerator = new OAuth2RefreshTokenGenerator();
        return new DelegatingOAuth2TokenGenerator(jwtGenerator, accessTokenGenerator, refreshTokenGenerator);
    }

    private static MediaTypeRequestMatcher textHtmlRequests() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }
}
