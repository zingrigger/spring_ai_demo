package com.example.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the interactive authorization-code + PKCE flow against MockMvc: login,
 * organization selection, consent and the code exchange.
 */
public final class AuthorizationCodeFlow {

    public static final String CLIENT_ID = "auth-web-public";
    public static final String CLIENT_SECRET = "auth-web-secret";
    public static final String REDIRECT_URI = "http://127.0.0.1:8080/login/oauth2/code/auth-server";
    public static final String STATE = "test-state";
    public static final String VERIFIER = "0123456789012345678901234567890123456789012";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthorizationCodeFlow(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /**
     * Runs the whole flow and returns the token endpoint response.
     */
    public JsonNode authorizeAndExchangeTokens(String account, String password, long orgId, String scope)
            throws Exception {
        String code = authorize(account, password, orgId, scope);
        MvcResult tokens = this.mockMvc.perform(tokenExchange(code, REDIRECT_URI, VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(tokens.getResponse().getContentAsString());
    }

    /**
     * Token exchange for the user-facing client: the client authenticates with
     * its secret while PKCE stays enforced on top.
     */
    public static MockHttpServletRequestBuilder tokenExchange(String code, String redirectUri, String verifier) {
        return post("/oauth2/token")
                .with(httpBasic(CLIENT_ID, CLIENT_SECRET))
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("code_verifier", verifier);
    }

    public static MockHttpServletRequestBuilder refresh(String refreshToken) {
        return post("/oauth2/token")
                .with(httpBasic(CLIENT_ID, CLIENT_SECRET))
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken);
    }

    public static MockHttpServletRequestBuilder revoke(String token) {
        return post("/oauth2/revoke")
                .with(httpBasic(CLIENT_ID, CLIENT_SECRET))
                .param("token", token);
    }

    /**
     * Runs the flow up to the issued authorization code and returns it.
     */
    public String authorize(String account, String password, long orgId, String scope) throws Exception {
        MockHttpSession session = loginAndSelectOrganization(account, password, orgId, scope);
        MvcResult authorization = this.mockMvc.perform(authorizeRequest(scope, VERIFIER).session(session))
                .andReturn();

        String location;
        if (isClientRedirect(authorization)) {
            // Scopes that are implicitly approved (openid) skip the consent page.
            location = authorization.getResponse().getRedirectedUrl();
        }
        else {
            location = submitConsent(session, consentPayload(session, authorization));
        }
        assertThat(location).startsWith(REDIRECT_URI);
        assertThat(queryParameter(location, "state")).isEqualTo(STATE);
        return queryParameter(location, "code");
    }

    /**
     * 返回 consent 数据接口的 JSON 载荷（含客户端名称、scopes、state）。
     */
    public JsonNode consentPayload(String account, String password, long orgId, String scope) throws Exception {
        MockHttpSession session = loginAndSelectOrganization(account, password, orgId, scope);
        MvcResult authorization = this.mockMvc.perform(authorizeRequest(scope, VERIFIER).session(session))
                .andReturn();
        return consentPayload(session, authorization);
    }

    private JsonNode consentPayload(MockHttpSession session, MvcResult authorization) throws Exception {
        String location = authorization.getResponse().getRedirectedUrl();
        assertThat(location).as("authorization endpoint did not redirect to the consent page").isNotNull();
        assertThat(java.net.URI.create(location).getPath()).isEqualTo("/consent");
        Map<String, List<String>> parameters = queryParameters(location);

        MockHttpServletRequestBuilder request = get("/api/consent").session(session)
                .param("client_id", parameters.get("client_id").getFirst());
        parameters.getOrDefault("scope", List.of()).forEach((scope) -> request.param("scope", scope));
        if (parameters.containsKey("state")) {
            request.param("state", parameters.get("state").getFirst());
        }
        MvcResult result = this.mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpSession loginAndSelectOrganization(String account, String password, long orgId, String scope)
            throws Exception {
        MvcResult start = this.mockMvc.perform(authorizeRequest(scope, VERIFIER))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        MvcResult organizations = this.mockMvc.perform(get("/api/organizations").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode payload = this.objectMapper.readTree(organizations.getResponse().getContentAsString());
        if (payload.path("next").isNull() || payload.path("next").isMissingNode()) {
            this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orgId\":" + orgId + "}"))
                    .andExpect(status().isOk());
        }
        return session;
    }

    private static boolean isClientRedirect(MvcResult authorization) {
        String location = authorization.getResponse().getRedirectedUrl();
        return authorization.getResponse().getStatus() == 302
                && location != null
                && location.startsWith(REDIRECT_URI);
    }

    private static Map<String, List<String>> queryParameters(String url) {
        Map<String, List<String>> decoded = new java.util.LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().forEach(
                (name, values) -> decoded.put(name, values.stream()
                        .map((value) -> java.net.URLDecoder.decode(value, StandardCharsets.UTF_8))
                        .toList()));
        return decoded;
    }

    private String submitConsent(MockHttpSession session, JsonNode consent) throws Exception {
        MockHttpServletRequestBuilder request = post("/oauth2/authorize").session(session)
                .param("client_id", consent.path("clientId").asText());
        if (consent.hasNonNull("state")) {
            request.param("state", consent.path("state").asText());
        }
        consent.path("scopes").forEach((scope) -> request.param("scope", scope.asText()));
        return this.mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }

    static MockHttpServletRequestBuilder authorizeRequest(String scope, String verifier) throws Exception {
        return get("/oauth2/authorize")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("scope", scope)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("state", STATE)
                .queryParam("code_challenge", codeChallenge(verifier))
                .queryParam("code_challenge_method", "S256");
    }

    private static String codeChallenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String queryParameter(String url, String name) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
    }
}
