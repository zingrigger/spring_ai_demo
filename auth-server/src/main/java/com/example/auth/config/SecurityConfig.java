package com.example.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

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
                .requestMatchers("/login", "/error").permitAll()
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
    RequestCache requestCache() {
        return new HttpSessionRequestCache();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
