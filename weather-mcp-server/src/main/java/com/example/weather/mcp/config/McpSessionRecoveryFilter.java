package com.example.weather.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.UUID;

/**
 * Streamable HTTP 会话恢复过滤器。
 *
 * <p>MCP 规范（Streamable HTTP, §Session Management）要求：服务器收到未知或
 * 已失效的 {@code Mcp-Session-Id} 时，应返回 HTTP 404（并携带新的
 * {@code Mcp-Session-Id} 响应头），让合规客户端据此重新发起 initialize 并
 * 自动恢复会话。Spring AI 2.0.1 的
 * {@code WebMvcStreamableServerTransportProvider} 对未知会话抛出
 * {@code McpError(-32603, "Session not found: ...")}，由 Spring MVC 异常解析器
 * 在 DispatcherServlet 内部处理为 {@code 404 + 私有 jsonRpcError 信封}：
 * 状态码符合规范，但缺少新的会话头、且响应体不是标准 JSON-RPC 形状。
 *
 * <p>本过滤器检测响应体中的 Session-not-found 错误（异常拦截作为兜底路径），
 * 将其改写为规范形态：{@code 404 + 新 Mcp-Session-Id 头 + 空响应体}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpSessionRecoveryFilter extends OncePerRequestFilter {

    /** Spring AI 对未知会话返回的 JSON-RPC 错误码。 */
    private static final int SESSION_NOT_FOUND_CODE = -32603;
    /** Spring AI 对未知会话抛出的 McpError 消息前缀。 */
    private static final String SESSION_NOT_FOUND_PREFIX = "Session not found";
    private static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 只关心 MCP 端点的 POST 请求（工具调用、initialize 等走 POST）。
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().endsWith("/mcp")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, wrapped);
            // 主路径：Spring MVC 异常解析器已将错误写进响应体（响应体为
            // {"jsonRpcError":{...}} 信封）。命中"会话失效"则改写并丢弃错误体。
            if (isSessionNotFoundBody(wrapped)) {
                rewrite(response);
                return;
            }
            wrapped.copyBodyToResponse();
        } catch (Exception error) {
            // 兜底路径：异常若未被解析器处理而冒泡到这里，同样改写并吞掉。
            if (isSessionNotFoundError(error)) {
                rewrite(response);
                return;
            }
            throw error;
        }
    }

    /** 判断缓存响应体是否为 Spring AI 的 "Session not found" jsonRpcError 信封。 */
    private boolean isSessionNotFoundBody(ContentCachingResponseWrapper wrapped) {
        byte[] body = wrapped.getContentAsByteArray();
        if (body.length == 0) return false;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("jsonRpcError");
            return !error.isMissingNode()
                    && error.path("code").asInt() == SESSION_NOT_FOUND_CODE
                    && error.path("message").asText().startsWith(SESSION_NOT_FOUND_PREFIX);
        } catch (IOException ignored) {
            return false;
        }
    }

    /** 沿异常链查找 Spring AI 的 "Session not found: ..." 错误。 */
    private boolean isSessionNotFoundError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.startsWith(SESSION_NOT_FOUND_PREFIX)) return true;
        }
        return false;
    }

    /** 将当前响应改写为 404 + 新会话 ID + 空响应体。 */
    private void rewrite(HttpServletResponse response) {
        try {
            // 异常/错误体路径下响应尚未提交，reset 安全；若已提交则放弃改写（保持原状）。
            response.reset();
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setHeader(MCP_SESSION_ID_HEADER, UUID.randomUUID().toString());
        } catch (IllegalStateException committed) {
            // 响应已提交，无法改写。
        }
    }
}
