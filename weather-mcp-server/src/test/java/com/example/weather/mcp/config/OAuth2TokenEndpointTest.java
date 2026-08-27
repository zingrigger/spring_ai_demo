package com.example.weather.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityTestConfiguration.class)
class OAuth2TokenEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void issuesAccessTokenViaClientCredentials() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(httpBasic("weather-mcp-machine", "demo-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "weather:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("weather:read"));
    }

    @Test
    void rejectsInvalidClientCredentials() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .with(httpBasic("weather-mcp-machine", "wrong-secret"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }
}
