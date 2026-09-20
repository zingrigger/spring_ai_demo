package com.example.auth.clientadmin;

import java.util.List;

/**
 * 创建客户端的表单输入；type 只是预设，最终展示类型由落库的 grant/cert 推导。
 */
public record CreateClientCommand(String clientId, String clientName, String type, List<String> redirectUris,
                                  List<String> postLogoutRedirectUris, List<String> scopes,
                                  Boolean requireAuthorizationConsent) {
}
