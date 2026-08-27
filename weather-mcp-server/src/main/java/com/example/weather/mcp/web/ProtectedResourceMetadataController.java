package com.example.weather.mcp.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProtectedResourceMetadataController {

    static final String BASE_URL = "http://localhost:8081";

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", BASE_URL + "/mcp",
                "authorization_servers", List.of(BASE_URL));
    }
}
