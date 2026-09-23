package com.example.auth.web;

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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JSON sign-in API used by the SPA: session probe, login and logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class AuthApiControllerTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousSessionHasNoUser() throws Exception {
        this.mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void loginStoresTheSessionAndReturnsTheNextStep() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "alice", "alice-password");

        this.mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.account").value("alice"))
                .andExpect(jsonPath("$.organization").doesNotExist());
    }

    @Test
    void loginSendsAnOrganizationlessPlatformAdminToTheHomePage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"superadmin\",\"password\":\"test@123..\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("/"));

        this.mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.organization").doesNotExist());
    }

    @Test
    void sessionExposesThePendingAuthorizationRequest() throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();
        login(session, "alice", "alice-password");

        this.mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(containsString("/oauth2/authorize")));
    }

    @Test
    void unknownAccountsAndWrongPasswordsFailIdentically() throws Exception {
        this.mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));

        this.mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void loginWithoutACsrfTokenIsRejected() throws Exception {
        this.mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"alice-password\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "alice", "alice-password");

        this.mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }

    private void login(MockHttpSession session, String account, String password) throws Exception {
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("/organizations"));
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
