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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            location = submitConsent(session, consentPageHtml(session, authorization));
        }
        assertThat(location).startsWith(REDIRECT_URI);
        assertThat(queryParameter(location, "state")).isEqualTo(STATE);
        return queryParameter(location, "code");
    }

    /**
     * Returns the rendered authorization confirmation page for the given request.
     */
    public String consentPage(String account, String password, long orgId, String scope) throws Exception {
        MockHttpSession session = loginAndSelectOrganization(account, password, orgId, scope);
        MvcResult authorization = this.mockMvc.perform(authorizeRequest(scope, VERIFIER).session(session))
                .andReturn();
        return consentPageHtml(session, authorization);
    }

    private MockHttpSession loginAndSelectOrganization(String account, String password, long orgId, String scope)
            throws Exception {
        MvcResult start = this.mockMvc.perform(authorizeRequest(scope, VERIFIER))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
        assertThat(session).isNotNull();

        this.mockMvc.perform(post("/login").session(session).with(csrf())
                        .param("username", account)
                        .param("password", password))
                .andExpect(status().is3xxRedirection());

        this.mockMvc.perform(post("/organizations").session(session).with(csrf())
                        .param("orgId", String.valueOf(orgId)))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    /**
     * The authorization endpoint either renders the consent page inline or
     * redirects to the configured {@code /oauth2/consent} page; the redirect is
     * followed here with the query parameters decoded.
     */
    private String consentPageHtml(MockHttpSession session, MvcResult authorization) throws Exception {
        if (authorization.getResponse().getStatus() == 200) {
            return authorization.getResponse().getContentAsString();
        }
        String location = authorization.getResponse().getRedirectedUrl();
        assertThat(location).as("authorization endpoint did not render or redirect to a consent page").isNotNull();

        MockHttpServletRequestBuilder request = get(location.startsWith("http://")
                ? java.net.URI.create(location).getPath()
                : location.substring(0, location.indexOf('?') > 0 ? location.indexOf('?') : location.length()));
        queryParameters(location).forEach((name, value) -> request.param(name, value));
        return this.mockMvc.perform(request.session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static boolean isClientRedirect(MvcResult authorization) {
        String location = authorization.getResponse().getRedirectedUrl();
        return authorization.getResponse().getStatus() == 302
                && location != null
                && location.startsWith(REDIRECT_URI);
    }

    private static java.util.Map<String, String> queryParameters(String url) {
        java.util.Map<String, String> decoded = new java.util.LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams().forEach(
                (name, values) -> decoded.put(name, java.net.URLDecoder.decode(values.getFirst(), StandardCharsets.UTF_8)));
        return decoded;
    }

    private String submitConsent(MockHttpSession session, String consentHtml) throws Exception {
        return this.mockMvc.perform(post("/oauth2/authorize").session(session)
                        .param("client_id", CLIENT_ID)
                        .param("state", hiddenField(consentHtml, "state"))
                        .param("scope", scopesOf(consentHtml).toArray(String[]::new)))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }

    private static String hiddenField(String html, String name) {
        return inputValues(html, name).stream().findFirst()
                .orElseThrow(() -> new AssertionError("authorization page does not expose field " + name));
    }

    private static List<String> scopesOf(String html) {
        return inputValues(html, "scope");
    }

    private static List<String> inputValues(String html, String name) {
        Pattern inputTag = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern nameAttribute = Pattern.compile("name=\"" + Pattern.quote(name) + "\"");
        Pattern valueAttribute = Pattern.compile("value=\"([^\"]*)\"");
        List<String> values = new ArrayList<>();
        Matcher inputs = inputTag.matcher(html);
        while (inputs.find()) {
            String tag = inputs.group();
            Matcher value = valueAttribute.matcher(tag);
            if (nameAttribute.matcher(tag).find() && value.find()) {
                values.add(value.group(1));
            }
        }
        return values;
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
