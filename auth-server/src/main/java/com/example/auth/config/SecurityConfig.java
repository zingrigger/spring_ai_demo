package com.example.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

import java.util.Map;

/**
 * Security for the login and organization pages. The authorization server
 * endpoints are configured separately in {@link AuthorizationServerConfig}.
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http,
                                                       SecurityContextRepository securityContextRepository) throws Exception {
        http.securityContext((securityContext) -> securityContext
                .securityContextRepository(securityContextRepository));
        http.authorizeHttpRequests((authorize) -> authorize
                .requestMatchers("/login", "/error", "/actuator/health",
                        "/api/auth/session", "/api/auth/login").permitAll()
                .anyRequest().authenticated());
        http.formLogin((formLogin) -> formLogin
                .loginPage("/login")
                // Organization selection always follows a successful login; the
                // pending authorization request stays in the request cache.
                .successHandler((request, response, authentication) -> response.sendRedirect("/organizations"))
                .permitAll());
        http.logout((logout) -> logout.logoutSuccessUrl("/login"));
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        // 与框架共用同一个 AuthenticationManager：它已包含 @Component IdentityAuthenticationProvider。
        return configuration.getAuthenticationManager();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        // 登录改为自定义 JSON 端点后，会话固定防护必须显式声明。
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    RequestCache requestCache() {
        return new HttpSessionRequestCache();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Matches both the BCrypt hashes stored in sys_user and the
        // {bcrypt}-prefixed secrets stored in oauth2_registered_client: the
        // DefaultPasswordEncoderForMatches covers values without a {id} prefix.
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(
                "bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
}
