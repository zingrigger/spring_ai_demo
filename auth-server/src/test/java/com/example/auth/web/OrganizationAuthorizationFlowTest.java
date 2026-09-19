package com.example.auth.web;

import com.example.auth.security.OrganizationAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login plus organization selection, with the pending authorization request
 * cached in the session and resumed after the organization is bound.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class OrganizationAuthorizationFlowTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedAuthorizationRequestRedirectsToLogin() throws Exception {
        this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPageRendersACsrfToken() throws Exception {
        this.mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
    }

    @Test
    void unknownAccountsAndWrongPasswordsFailIdentically() throws Exception {
        this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", "nobody").param("password", "whatever"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        this.mockMvc.perform(post("/login").with(csrf())
                        .param("username", "alice").param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void multiOrganizationUserCanChooseBetweenBothOrganizations() throws Exception {
        MockHttpSession session = login("alice", "alice-password");

        this.mockMvc.perform(get("/organizations").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alpha")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Beta")));
    }

    @Test
    void selectingAnOrganizationBindsOnlyItsRolesAndResumesTheRequest() throws Exception {
        MockHttpSession session = login("alice", "alice-password");

        MvcResult selection = this.mockMvc.perform(post("/organizations").session(session).with(csrf()).param("orgId", "20"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(selection.getResponse().getRedirectedUrl()).startsWith("http://localhost/oauth2/authorize?");

        OrganizationAuthorization binding = binding(session);
        assertThat(binding).isNotNull();
        assertThat(binding.userId()).isEqualTo(1L);
        assertThat(binding.orgId()).isEqualTo(20L);
        assertThat(binding.orgName()).isEqualTo("Beta");
        assertThat(binding.roles()).containsExactly("AUDITOR");
    }

    @Test
    void rejectsAnOrganizationTheUserDoesNotBelongTo() throws Exception {
        MockHttpSession session = login("alice", "alice-password");

        this.mockMvc.perform(post("/organizations").session(session).with(csrf()).param("orgId", "30"))
                .andExpect(status().isForbidden());

        assertThat(binding(session)).isNull();
    }

    @Test
    void singleOrganizationUserContinuesWithoutChoosing() throws Exception {
        MockHttpSession session = login("bob", "bob-password");

        MvcResult selection = this.mockMvc.perform(get("/organizations").session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(selection.getResponse().getRedirectedUrl()).startsWith("http://localhost/oauth2/authorize?");

        OrganizationAuthorization binding = binding(session);
        assertThat(binding).isNotNull();
        assertThat(binding.orgId()).isEqualTo(20L);
        assertThat(binding.roles()).containsExactly("VIEWER");
    }

    private MockHttpSession login(String account, String password) throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/login").session(session).with(csrf())
                        .param("username", account)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/organizations"));
        return session;
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

    private static OrganizationAuthorization binding(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null || context.getAuthentication() == null) {
            return null;
        }
        Object details = context.getAuthentication().getDetails();
        return details instanceof OrganizationAuthorization authorization ? authorization : null;
    }
}
