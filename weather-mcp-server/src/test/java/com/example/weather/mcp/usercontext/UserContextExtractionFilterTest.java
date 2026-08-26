package com.example.weather.mcp.usercontext;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserContextExtractionFilterTest {

    private static final String SECRET = "demo-secret-change-me-0123456789abcdef";

    private final FilterChain filterChain = mock(FilterChain.class);
    private JwtTokenParser tokenParser;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        tokenParser = new JwtTokenParser(SECRET);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private static String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim("userId", "1001")
                .claim("tenantId", "acme")
                .signWith(key)
                .compact();
    }

    @Test
    void bearerModeExtractsClaimsIntoHolder() throws Exception {
        request.addHeader("Authorization", "Bearer " + validToken());
        doAnswer(invocation -> {
            assertThat(UserContextHolder.get()).isEqualTo(new UserContext("1001", "acme"));
            return null;
        }).when(filterChain).doFilter(request, response);
        new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void bearerModeLeavesHolderEmptyWithoutAuthorizationHeader() throws Exception {
        new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void bearerModeLeavesHolderEmptyForMalformedToken() throws Exception {
        request.addHeader("Authorization", "Bearer not-a-jwt");
        new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void explicitModeReadsHeadersIntoHolder() throws Exception {
        request.addHeader("X-User-Id", "1001");
        request.addHeader("X-User-Tenant", "acme");
        doAnswer(invocation -> {
            assertThat(UserContextHolder.get()).isEqualTo(new UserContext("1001", "acme"));
            return null;
        }).when(filterChain).doFilter(request, response);
        new UserContextExtractionFilter("explicit-headers", tokenParser)
                .doFilter(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void explicitModeLeavesHolderEmptyWithoutHeaders() throws Exception {
        new UserContextExtractionFilter("explicit-headers", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void explicitModeTreatsBlankHeadersAsMissing() throws Exception {
        request.addHeader("X-User-Id", "");
        doAnswer(invocation -> {
            assertThat(UserContextHolder.get()).isNull();
            return null;
        }).when(filterChain).doFilter(request, response);
        new UserContextExtractionFilter("explicit-headers", tokenParser)
                .doFilter(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void clearsHolderAfterRequest() throws Exception {
        request.addHeader("Authorization", "Bearer " + validToken());
        new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain);
        assertThat(UserContextHolder.get()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void clearsHolderWhenChainThrows() throws Exception {
        request.addHeader("Authorization", "Bearer " + validToken());
        RuntimeException failure = new RuntimeException("chain failed");
        doThrow(failure).when(filterChain).doFilter(request, response);
        assertThatThrownBy(() -> new UserContextExtractionFilter("bearer-token", tokenParser)
                .doFilter(request, response, filterChain))
                .isSameAs(failure);
        assertThat(UserContextHolder.get()).isNull();
    }
}
