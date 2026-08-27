package com.example.weather.mcp.config;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityTestConfiguration.class)
class McpSecurityFilterTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";

    @Test
    void unauthorizedWhenNoToken() throws Exception {
        MvcResult result = mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isUnauthorized())
                .andReturn();
        String challenge = result.getResponse().getHeader("WWW-Authenticate");
        assertThat(challenge).contains("resource_metadata=");
        assertThat(challenge).contains("scope=\"weather:read\"");
    }

    @Test
    @WithMockUser(authorities = "ROLE_OTHER")
    void forbiddenWithoutRequiredScope() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "SCOPE_weather:read")
    void allowedWithRequiredScope() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    @Test
    void acceptsAuthorizationServerIssuedToken() throws Exception {
        String token = obtainAccessToken();
        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    private String obtainAccessToken() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/token")
                        .with(httpBasic("weather-mcp-machine", "demo-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "weather:read"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.get("access_token").asText();
    }
}
