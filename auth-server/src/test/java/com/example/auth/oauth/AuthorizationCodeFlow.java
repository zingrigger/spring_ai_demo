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
    String authorize(String account, String password, long orgId, String scope) throws Exception {
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

        // Back at the authorization endpoint. When the client still needs consent, the
        // default consent page is rendered with an internal state token; scopes that are
        // implicitly approved (openid) skip the page and redirect straight back.
        MvcResult consentPage = this.mockMvc.perform(authorizeRequest(scope, VERIFIER).session(session))
                .andReturn();
        String location;
        if (consentPage.getResponse().getStatus() == 302) {
            location = consentPage.getResponse().getRedirectedUrl();
        }
        else {
            assertThat(consentPage.getResponse().getStatus()).isEqualTo(200);
            String consentHtml = consentPage.getResponse().getContentAsString();
            MvcResult consent = this.mockMvc.perform(post("/oauth2/authorize").session(session)
                            .param("client_id", CLIENT_ID)
                            .param("state", hiddenField(consentHtml, "state"))
                            .param("scope", scopesOf(consentHtml).toArray(String[]::new)))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            location = consent.getResponse().getRedirectedUrl();
        }
        assertThat(location).startsWith(REDIRECT_URI);
        assertThat(queryParameter(location, "state")).isEqualTo(STATE);
        return queryParameter(location, "code");
    }

    private static String hiddenField(String html, String name) {
        Matcher matcher = Pattern.compile("name=\"" + Pattern.quote(name) + "\" value=\"([^\"]*)\"").matcher(html);
        assertThat(matcher.find()).as("consent page exposes hidden field %s", name).isTrue();
        return matcher.group(1);
    }

    private static List<String> scopesOf(String html) {
        Matcher matcher = Pattern.compile("name=\"scope\" value=\"([^\"]*)\"").matcher(html);
        List<String> scopes = new ArrayList<>();
        while (matcher.find()) {
            scopes.add(matcher.group(1));
        }
        return scopes;
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
