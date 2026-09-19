package com.example.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.util.Map;
import java.util.Set;

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
                .requestMatchers("/", "/login", "/organizations", "/consent", "/assets/**", "/favicon.ico",
                        "/api/auth/session", "/api/auth/login", "/error", "/actuator/health").permitAll()
                .anyRequest().authenticated());
        // SPA 场景：cookie 里的原始 token 直接作为 X-XSRF-TOKEN 发送，因此使用明文处理器。
        http.csrf((csrf) -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        http.exceptionHandling((exceptions) -> exceptions
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        (request) -> request.getRequestURI().startsWith("/api/"))
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                        htmlRequests()));
        // 登出改由 POST /api/auth/logout 处理，避免保留第二套表单登出端点。
        http.logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static MediaTypeRequestMatcher htmlRequests() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
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
