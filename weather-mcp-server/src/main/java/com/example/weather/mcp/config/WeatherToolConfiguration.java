package com.example.weather.mcp.config;

import com.example.weather.mcp.tool.WeatherTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WeatherToolConfiguration {

    @Bean
    ToolCallbackProvider weatherToolCallbackProvider(WeatherTool weatherTool) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherTool).build();
    }
}
