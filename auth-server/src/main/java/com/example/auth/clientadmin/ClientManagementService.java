package com.example.auth.clientadmin;

import com.example.auth.client.ClientManagementSettings;
import com.example.auth.config.AuthServerProperties;
import com.example.auth.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 客户端管理的唯一写入口：校验、secret 生成与哈希、仓储写入与审计。
 */
@Service
public class ClientManagementService {

    static final Pattern CLIENT_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{2,63}$");

    static final Pattern SCOPE_PATTERN = Pattern.compile("^[A-Za-z0-9:_.-]{1,64}$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final JdbcRegisteredClientRepository registeredClientRepository;

    private final ClientQueryRepository clientQueryRepository;

    private final ClientAuditRepository clientAuditRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthServerProperties properties;

    public ClientManagementService(JdbcRegisteredClientRepository registeredClientRepository,
                                   ClientQueryRepository clientQueryRepository,
                                   ClientAuditRepository clientAuditRepository, PasswordEncoder passwordEncoder,
                                   AuthServerProperties properties) {
        this.registeredClientRepository = registeredClientRepository;
        this.clientQueryRepository = clientQueryRepository;
        this.clientAuditRepository = clientAuditRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    public List<ClientSummaryView> list(String query) {
        return this.clientQueryRepository.findAll(query);
    }

    public ClientDetailView get(String clientId) {
        return toDetail(requireClient(clientId));
    }

    @Transactional
    public CreatedClient create(CreateClientCommand command, AuthenticatedUser actor) {
        validateClientId(command.clientId());
        if (this.registeredClientRepository.findByClientId(command.clientId()) != null) {
            throw new ClientManagementException(HttpStatus.CONFLICT, "client_id_taken", List.of());
        }
        String type = normalizeType(command.type());
        List<String> redirectUris = validateUris(command.redirectUris(), "redirectUris");
        List<String> postLogoutRedirectUris = validateUris(command.postLogoutRedirectUris(),
                "postLogoutRedirectUris");
        List<String> scopes = validateScopes(command.scopes());
        if (!"machine".equals(type) && redirectUris.isEmpty()) {
            throw invalid("redirectUris", "required");
        }
        boolean requireConsent = command.requireAuthorizationConsent() == null
                ? !"machine".equals(type) : command.requireAuthorizationConsent();

        RegisteredClient.Builder builder = clientBuilder(command.clientId(), command.clientName(), type,
                redirectUris, postLogoutRedirectUris, scopes, requireConsent);
        String clientSecret = null;
        if (!"public".equals(type)) {
            clientSecret = generateSecret();
            builder.clientSecret(this.passwordEncoder.encode(clientSecret));
        }
        RegisteredClient saved = builder.build();
        this.registeredClientRepository.save(saved);
        this.clientAuditRepository.record(saved.getId(), saved.getClientId(), AuditAction.CREATE, actor, List.of());
        return new CreatedClient(toDetail(saved), clientSecret);
    }

    @Transactional
    public ClientDetailView update(String clientId, UpdateClientCommand command, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        List<String> grantTypes = command.grantTypes() == null
                ? values(existing.getAuthorizationGrantTypes(), AuthorizationGrantType::getValue)
                : normalizeGrantTypes(command.grantTypes());
        List<String> authenticationMethods = command.clientAuthenticationMethods() == null
                ? values(existing.getClientAuthenticationMethods(), ClientAuthenticationMethod::getValue)
                : normalizeAuthenticationMethods(command.clientAuthenticationMethods());
        validateCombination(grantTypes, authenticationMethods);
        List<String> redirectUris = validateUris(command.redirectUris() == null
                ? List.copyOf(existing.getRedirectUris()) : command.redirectUris(), "redirectUris");
        List<String> postLogoutRedirectUris = validateUris(command.postLogoutRedirectUris() == null
                ? List.copyOf(existing.getPostLogoutRedirectUris()) : command.postLogoutRedirectUris(),
                "postLogoutRedirectUris");
        List<String> scopes = command.scopes() == null
                ? List.copyOf(existing.getScopes()) : validateScopes(command.scopes());
        if (grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue()) && redirectUris.isEmpty()) {
            throw invalid("redirectUris", "required");
        }
        boolean publicClient = authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue());
        if (publicClient && Boolean.FALSE.equals(command.requireProofKey())) {
            throw invalid("requireProofKey", "public_client_requires_pkce");
        }
        boolean requireProofKey = publicClient || (command.requireProofKey() == null
                ? existing.getClientSettings().isRequireProofKey() : command.requireProofKey());
        boolean requireConsent = command.requireAuthorizationConsent() == null
                ? existing.getClientSettings().isRequireAuthorizationConsent()
                : command.requireAuthorizationConsent();

        ClientSettings.Builder settings = ClientSettings.withSettings(existing.getClientSettings().getSettings());
        settings.requireProofKey(requireProofKey);
        settings.requireAuthorizationConsent(requireConsent);

        RegisteredClient.Builder builder = RegisteredClient.from(existing)
                .clientName(command.clientName() == null ? existing.getClientName() : command.clientName())
                .redirectUris((uris) -> {
                    uris.clear();
                    uris.addAll(redirectUris);
                })
                .postLogoutRedirectUris((uris) -> {
                    uris.clear();
                    uris.addAll(postLogoutRedirectUris);
                })
                .scopes((values) -> {
                    values.clear();
                    values.addAll(scopes);
                })
                .authorizationGrantTypes((values) -> {
                    values.clear();
                    grantTypes.forEach((value) -> values.add(new AuthorizationGrantType(value)));
                })
                .clientAuthenticationMethods((values) -> {
                    values.clear();
                    authenticationMethods.forEach((value) -> values.add(new ClientAuthenticationMethod(value)));
                })
                .clientSettings(settings.build());
        if (authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue())) {
            builder.clientSecret(null);
        }
        RegisteredClient updated = builder.build();
        this.registeredClientRepository.save(updated);
        this.clientAuditRepository.record(existing.getId(), existing.getClientId(), AuditAction.UPDATE, actor,
                changedFields(command));
        return toDetail(updated);
    }

    @Transactional
    public String rotateSecret(String clientId, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        if (existing.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)) {
            throw invalid("clientAuthenticationMethods", "public_client_has_no_secret");
        }
        String clientSecret = generateSecret();
        RegisteredClient updated = RegisteredClient.from(existing)
                .clientSecret(this.passwordEncoder.encode(clientSecret))
                .build();
        this.registeredClientRepository.save(updated);
        this.clientAuditRepository.record(existing.getId(), clientId, AuditAction.ROTATE_SECRET, actor, List.of());
        return clientSecret;
    }

    @Transactional
    public ClientDetailView setEnabled(String clientId, boolean enabled, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        RegisteredClient updated = ClientManagementSettings.withEnabled(existing, enabled);
        this.registeredClientRepository.save(updated);
        if (!enabled) {
            this.clientQueryRepository.deleteAuthorizationsAndConsents(existing.getId());
        }
        this.clientAuditRepository.record(existing.getId(), clientId,
                enabled ? AuditAction.ENABLE : AuditAction.DISABLE, actor, List.of());
        return toDetail(updated);
    }

    @Transactional
    public void delete(String clientId, AuthenticatedUser actor) {
        RegisteredClient existing = requireClient(clientId);
        this.clientQueryRepository.deleteAuthorizationsAndConsents(existing.getId());
        this.clientQueryRepository.deleteRegisteredClient(existing.getId());
        this.clientAuditRepository.record(existing.getId(), clientId, AuditAction.DELETE, actor, List.of());
    }

    private static List<String> normalizeGrantTypes(List<String> grantTypes) {
        List<String> allowed = List.of("authorization_code", "client_credentials", "refresh_token");
        List<String> normalized = new ArrayList<>();
        for (String value : grantTypes) {
            if (value == null || !allowed.contains(value)) {
                throw invalid("grantTypes", "unsupported");
            }
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw invalid("grantTypes", "required");
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeAuthenticationMethods(List<String> authenticationMethods) {
        List<String> allowed = List.of("client_secret_basic", "client_secret_post", "none");
        List<String> normalized = new ArrayList<>();
        for (String value : authenticationMethods) {
            if (value == null || !allowed.contains(value)) {
                throw invalid("clientAuthenticationMethods", "unsupported");
            }
            if (!normalized.contains(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw invalid("clientAuthenticationMethods", "required");
        }
        return List.copyOf(normalized);
    }

    private static void validateCombination(List<String> grantTypes, List<String> authenticationMethods) {
        if (authenticationMethods.contains(ClientAuthenticationMethod.NONE.getValue())) {
            boolean onlyAuthorizationCode = grantTypes.size() == 1
                    && grantTypes.contains(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
            if (!onlyAuthorizationCode) {
                throw invalid("clientAuthenticationMethods", "public_client_requires_authorization_code");
            }
        }
    }

    private static List<String> changedFields(UpdateClientCommand command) {
        List<String> fields = new ArrayList<>();
        if (command.clientName() != null) {
            fields.add("clientName");
        }
        if (command.redirectUris() != null) {
            fields.add("redirectUris");
        }
        if (command.postLogoutRedirectUris() != null) {
            fields.add("postLogoutRedirectUris");
        }
        if (command.scopes() != null) {
            fields.add("scopes");
        }
        if (command.grantTypes() != null) {
            fields.add("grantTypes");
        }
        if (command.clientAuthenticationMethods() != null) {
            fields.add("clientAuthenticationMethods");
        }
        if (command.requireAuthorizationConsent() != null) {
            fields.add("requireAuthorizationConsent");
        }
        if (command.requireProofKey() != null) {
            fields.add("requireProofKey");
        }
        return fields;
    }

    private RegisteredClient.Builder clientBuilder(String clientId, String clientName, String type,
                                                  List<String> redirectUris, List<String> postLogoutRedirectUris,
                                                  List<String> scopes, boolean requireConsent) {
        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientIdIssuedAt(Instant.now())
                .clientName(clientName)
                .redirectUris((uris) -> uris.addAll(redirectUris))
                .postLogoutRedirectUris((uris) -> uris.addAll(postLogoutRedirectUris))
                .scopes((values) -> values.addAll(scopes))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(!"machine".equals(type))
                        .requireAuthorizationConsent(requireConsent)
                        .build())
                .tokenSettings(tokenSettings());
        switch (type) {
            case "web" -> builder
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
            case "machine" -> builder
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
            case "public" -> builder
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
            default -> throw invalid("type", "unsupported");
        }
        return builder;
    }

    private TokenSettings tokenSettings() {
        return TokenSettings.builder()
                .authorizationCodeTimeToLive(this.properties.oauth().authorizationCodeTtl())
                .accessTokenTimeToLive(this.properties.oauth().accessTokenTtl())
                .refreshTokenTimeToLive(this.properties.oauth().refreshTokenTtl())
                .reuseRefreshTokens(false)
                .build();
    }

    private RegisteredClient requireClient(String clientId) {
        RegisteredClient client = this.registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            throw new ClientManagementException(HttpStatus.NOT_FOUND, "client_not_found", List.of());
        }
        return client;
    }

    static ClientDetailView toDetail(RegisteredClient client) {
        TokenSettings tokenSettings = client.getTokenSettings();
        return new ClientDetailView(
                client.getClientId(),
                client.getClientName(),
                ClientTypeResolver.resolve(client),
                values(client.getClientAuthenticationMethods(), ClientAuthenticationMethod::getValue),
                values(client.getAuthorizationGrantTypes(), AuthorizationGrantType::getValue),
                List.copyOf(client.getRedirectUris()),
                List.copyOf(client.getPostLogoutRedirectUris()),
                sorted(client.getScopes()),
                client.getClientSettings().isRequireProofKey(),
                client.getClientSettings().isRequireAuthorizationConsent(),
                client.getClientIdIssuedAt(),
                client.getClientSecretExpiresAt(),
                ClientManagementSettings.isEnabled(client),
                new ClientDetailView.TokenSettingsView(
                        tokenSettings.getAccessTokenTimeToLive().toString(),
                        tokenSettings.getRefreshTokenTimeToLive().toString(),
                        tokenSettings.getAuthorizationCodeTimeToLive().toString(),
                        tokenSettings.getIdTokenSignatureAlgorithm().getName(),
                        tokenSettings.getAccessTokenFormat().getValue()));
    }

    private static <T> List<String> values(Collection<T> source, Function<T, String> mapper) {
        return source.stream().map(mapper).toList();
    }

    /**
     * 框架用 HashSet 保存 scopes，顺序不稳定；读模型统一按字典序输出，便于比对与展示。
     */
    private static List<String> sorted(Collection<String> values) {
        return values.stream().filter((value) -> value != null).sorted().toList();
    }

    private static void validateClientId(String clientId) {
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw invalid("clientId", "invalid_format");
        }
    }

    private static String normalizeType(String type) {
        String value = type == null ? "" : type.trim();
        if (!List.of("web", "machine", "public").contains(value)) {
            throw invalid("type", "unsupported");
        }
        return value;
    }

    private static List<String> validateUris(List<String> uris, String field) {
        if (uris == null) {
            return List.of();
        }
        List<String> validated = new ArrayList<>();
        for (String value : uris) {
            if (value == null || value.isBlank()) {
                continue;
            }
            validated.add(validateUri(value.trim(), field));
        }
        return List.copyOf(validated);
    }

    private static String validateUri(String value, String field) {
        URI uri;
        try {
            uri = new URI(value);
        }
        catch (URISyntaxException ex) {
            throw invalid(field, "invalid_uri");
        }
        if (!uri.isAbsolute() || uri.getFragment() != null) {
            throw invalid(field, "invalid_uri");
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return value;
        }
        if ("http".equalsIgnoreCase(scheme) && isLoopback(uri.getHost())) {
            return value;
        }
        throw invalid(field, "invalid_uri");
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    private static List<String> validateScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw invalid("scopes", "required");
        }
        List<String> validated = new ArrayList<>();
        for (String scope : scopes) {
            if (scope == null || !SCOPE_PATTERN.matcher(scope).matches()) {
                throw invalid("scopes", "invalid_scope");
            }
            if (!validated.contains(scope)) {
                validated.add(scope);
            }
        }
        return List.copyOf(validated);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return BASE64_ENCODER.encodeToString(bytes);
    }

    private static ClientManagementException invalid(String field, String code) {
        return new ClientManagementException(HttpStatus.BAD_REQUEST, "invalid_client_metadata",
                List.of(new ClientManagementException.Detail(field, code)));
    }

    public record CreatedClient(ClientDetailView client, String clientSecret) {
    }
}
