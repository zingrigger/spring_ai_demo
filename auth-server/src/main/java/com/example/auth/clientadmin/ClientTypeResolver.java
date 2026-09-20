package com.example.auth.clientadmin;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Collection;

/**
 * 展示类型由 grant types 与认证方式推导；匹配不到预设时返回 custom。
 */
public final class ClientTypeResolver {

    private ClientTypeResolver() {
    }

    public static String resolve(RegisteredClient client) {
        return resolve(
                client.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue).toList(),
                client.getClientAuthenticationMethods().stream().map(ClientAuthenticationMethod::getValue).toList());
    }

    public static String resolve(Collection<String> grantTypes, Collection<String> authenticationMethods) {
        boolean publicClient = authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue());
        if (publicClient && grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())) {
            return "public";
        }
        if (grantTypes.contains(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())) {
            return "machine";
        }
        if (grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())) {
            return "web";
        }
        return "custom";
    }
}
