package com.example.auth.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台管理员访问控制：只有 auth.admin.accounts 命中的账号能访问 /api/admin/**。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class PlatformAdminAccessTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousAdminRequestsAreRejectedWith401() throws Exception {
        this.mockMvc.perform(get("/api/admin/clients"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminsAreRejectedWith403Json() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "bob", "bob-password");

        this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    @Test
    void platformAdminsPassTheSecurityFilter() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "alice", "alice-password");

        this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminsWithoutAnOrganizationPassTheSecurityFilter() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "superadmin", "test@123..");

        this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void sessionExposesThePlatformAdminFlag() throws Exception {
        MockHttpSession adminSession = new MockHttpSession();
        login(adminSession, "alice", "alice-password");
        this.mockMvc.perform(get("/api/auth/session").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true));

        MockHttpSession userSession = new MockHttpSession();
        login(userSession, "bob", "bob-password");
        this.mockMvc.perform(get("/api/auth/session").session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(false));
    }

    private void login(MockHttpSession session, String account, String password) throws Exception {
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }
}
