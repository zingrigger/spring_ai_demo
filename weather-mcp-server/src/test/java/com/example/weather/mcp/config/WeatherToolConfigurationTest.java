package com.example.weather.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class WeatherToolConfigurationTest {

    @Autowired
    @Qualifier("weatherToolCallbackProvider")
    private ToolCallbackProvider provider;

    @Test
    void registersWeatherToolWithCityInputSchema() {
        assertThat(provider.getToolCallbacks()).singleElement().satisfies(callback -> {
            assertThat(callback.getToolDefinition().name()).isEqualTo("get_weather_by_city");
            assertThat(callback.getToolDefinition().description()).isEqualTo("查询指定城市的当前天气");
            assertThat(callback.getToolDefinition().inputSchema()).contains("city");
        });
    }
}
