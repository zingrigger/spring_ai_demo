package com.example.auth.clientadmin;

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
                List.copyOf(client.getScopes()),
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
