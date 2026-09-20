package com.example.auth.clientadmin;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理面写入 client_settings 的私有 setting；框架的 withSettings 会原样往返未知 key。
 */
public final class ClientManagementSettings {

    public static final String ENABLED = "com.example.auth.client.enabled";

    private ClientManagementSettings() {
    }

    public static boolean isEnabled(RegisteredClient client) {
        Object enabled = client.getClientSettings().getSettings().get(ENABLED);
        return !Boolean.FALSE.equals(enabled);
    }

    public static RegisteredClient withEnabled(RegisteredClient client, boolean enabled) {
        Map<String, Object> settings = new HashMap<>(client.getClientSettings().getSettings());
        settings.put(ENABLED, enabled);
        return RegisteredClient.from(client)
                .clientSettings(ClientSettings.withSettings(settings).build())
                .build();
    }
}
