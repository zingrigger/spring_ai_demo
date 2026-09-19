package com.example.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入口点分流：API 未认证返回 401 JSON，HTML 请求 302 到 SPA 的 /login；
 * 组织绑定后能继续被缓存的授权请求。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class AuthenticationEntryPointTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedAuthorizationRequestRedirectsToLogin() throws Exception {
        this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unauthenticatedApiRequestsAreRejectedWith401() throws Exception {
        this.mockMvc.perform(get("/api/organizations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bindingTheOrganizationResumesThePendingAuthorizationRequest() throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"alice-password\"}"))
                .andExpect(status().isOk());

        MvcResult selection = this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":20}"))
                .andExpect(status().isOk())
                .andReturn();
        String next = this.objectMapper.readTree(selection.getResponse().getContentAsString())
                .path("next").asText();
        assertThat(next).contains("/oauth2/authorize");

        MvcResult resumed = this.mockMvc.perform(authorizeRequest().session(session)).andReturn();
        String consentRedirect = resumed.getResponse().getRedirectedUrl();
        assertThat(consentRedirect).isNotNull();
        assertThat(java.net.URI.create(consentRedirect).getPath()).endsWith("/consent");
    }

    private static MockHttpServletRequestBuilder authorizeRequest() {
        return get("/oauth2/authorize")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .queryParam("response_type", "code")
                .queryParam("client_id", "auth-web-public")
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/auth-server")
                .queryParam("state", "test-state")
                .queryParam("code_challenge", "0123456789012345678901234567890123456789012")
                .queryParam("code_challenge_method", "S256");
    }
}
