package com.example.weather.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class WeatherMcpServerApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void loadsStreamableMcpConfiguration() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("weather-mcp-server");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STATELESS");
        assertThat(environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint")).isEqualTo("/mcp");
    }
}
