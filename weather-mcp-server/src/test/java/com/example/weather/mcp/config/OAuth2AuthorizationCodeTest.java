package com.example.weather.mcp.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the interactive authorization_code + PKCE flow works end to end with
 * the embedded authorization server and the form-login demo user: an
 * unauthenticated authorize request is redirected to /login, logging in returns
 * to the authorize step, the authorize step issues a code to the client, the
 * code is exchanged at the token endpoint, and the resulting access token is
 * accepted by the protected /mcp resource server.
 *
 * <p>Note: the authorize GETs must carry {@code Accept: text/html} (as a real
 * browser would) so the resource-server Bearer entry point does not shadow the
 * form-login entry point for unauthenticated requests, and the query string
 * must be explicit — the authorization server's parameter parsing filters
 * {@code getParameterMap()} through {@code getQueryString()}, which MockMvc's
 * {@code .param()} builder does not populate reliably.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityTestConfiguration.class)
class OAuth2AuthorizationCodeTest {

    private static final String CLIENT_ID = "weather-mcp-public";
    private static final String REDIRECT_URI = "http://127.0.0.1:5173/callback";
    /** RFC 7636 Appendix B example code verifier (43 chars, deterministic). */
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private static final String INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginPageIsReachable() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void authorizationCodeWithPkceProducesAccessToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String authorizeUrl = authorizeUrl(s256Challenge(CODE_VERIFIER));

        // 1. An unauthenticated authorize request is redirected to the login page.
        mvc.perform(get(authorizeUrl)
                        .session(session)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));

        // 2. Logging in with the demo user returns to the authorize flow.
        mvc.perform(post("/login")
                        .session(session)
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorize")));

        // 3. The now-authenticated authorize request redirects to the client
        //    with an authorization code (no consent page: consent disabled).
        MvcResult authorizeResult = mvc.perform(get(authorizeUrl)
                        .session(session)
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith(REDIRECT_URI + "?code=")))
                .andReturn();
        String code = extractQueryParam(authorizeResult.getResponse().getHeader("Location"), "code");
        assertThat(code).isNotBlank();

        // 4. Exchange the code (+ PKCE verifier) at the token endpoint.
        MvcResult tokenResult = mvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", CODE_VERIFIER)
                        .param("client_id", CLIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("weather:read"))
                .andReturn();

        JsonNode tokenBody = objectMapper.readTree(
                tokenResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String accessToken = tokenBody.get("access_token").asText();
        assertThat(accessToken).isNotBlank();

        // 5. The access token issued through the auth-code flow is accepted by
        //    the protected /mcp resource server (not 401/403; the MCP handler
        //    may answer 400 to a bare initialize without a session, exactly as
        //    in McpSecurityFilterTest).
        mvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INITIALIZE))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    private static String authorizeUrl(String codeChallenge) {
        return "/oauth2/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&redirect_uri=" + REDIRECT_URI
                + "&scope=weather:read"
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";
    }

    private static String s256Challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String extractQueryParam(String url, String name) {
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && keyValue[0].equals(name)) {
                return java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
