package com.example.auth.web;

import com.example.auth.security.OrganizationAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
 * 组织选择 API：列表、单组织自动绑定、越权拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class OrganizationApiControllerTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void multiOrganizationUserGetsTheListAndNoNextStep() throws Exception {
        MockHttpSession session = signedIn("alice", "alice-password");

        this.mockMvc.perform(get("/api/organizations").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations.length()").value(2))
                .andExpect(jsonPath("$.next").doesNotExist());
    }

    @Test
    void selectingAnOrganizationBindsItAndResumesThePendingRequest() throws Exception {
        MockHttpSession session = signedIn("alice", "alice-password");

        this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value(containsString("/oauth2/authorize")));

        OrganizationAuthorization binding = binding(session);
        assertThat(binding).isNotNull();
        assertThat(binding.orgId()).isEqualTo(20L);
        assertThat(binding.roles()).containsExactly("AUDITOR");
    }

    @Test
    void singleOrganizationUserIsBoundAutomatically() throws Exception {
        MockHttpSession session = signedIn("bob", "bob-password");

        this.mockMvc.perform(get("/api/organizations").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations.length()").value(1))
                .andExpect(jsonPath("$.next").value(containsString("/oauth2/authorize")));

        OrganizationAuthorization binding = binding(session);
        assertThat(binding).isNotNull();
        assertThat(binding.orgId()).isEqualTo(20L);
    }

    @Test
    void organizationsOfAnotherUserAreRejected() throws Exception {
        MockHttpSession session = signedIn("alice", "alice-password");

        this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":30}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    private MockHttpSession signedIn(String account, String password) throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private OrganizationAuthorization binding(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null || context.getAuthentication() == null) {
            return null;
        }
        Object details = context.getAuthentication().getDetails();
        return details instanceof OrganizationAuthorization authorization ? authorization : null;
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
