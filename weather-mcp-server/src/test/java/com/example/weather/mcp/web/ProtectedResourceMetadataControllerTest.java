package com.example.weather.mcp.web;

import com.example.weather.mcp.config.MockMvcSecurityTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityTestConfiguration.class)
class ProtectedResourceMetadataControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void exposesProtectedResourceMetadata() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost:8081/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value("http://localhost:8081"));
    }
}
