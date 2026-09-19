package com.example.weather.mcp.web;

import com.example.weather.mcp.config.WeatherMcpProperties;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProtectedResourceMetadataController {

    private final WeatherMcpProperties properties;

    public ProtectedResourceMetadataController(WeatherMcpProperties properties) {
        this.properties = properties;
    }

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", this.properties.resourceIdentifier(),
                "authorization_servers", List.of(this.properties.authorizationServerUrl()));
    }
}
