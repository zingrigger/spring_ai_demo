package com.example.auth.clientadmin;

import java.util.List;

/**
 * 编辑客户端的表单输入；null 字段表示保持原值，client_id 不可变。
 */
public record UpdateClientCommand(String clientName, List<String> redirectUris, List<String> postLogoutRedirectUris,
                                  List<String> scopes, List<String> grantTypes,
                                  List<String> clientAuthenticationMethods, Boolean requireAuthorizationConsent,
                                  Boolean requireProofKey) {
}
