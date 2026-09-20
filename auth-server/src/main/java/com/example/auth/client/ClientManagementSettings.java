package com.example.auth.client;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端管理写入 client_settings 的私有 setting；框架的 withSettings 会原样往返未知 key。
 *
 * <p>放在独立的 client 包：协议端点的装饰器（config）与管理面（clientadmin）都要用它，
 * 两边都只依赖这里，避免 config 反向依赖具体功能包。
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
