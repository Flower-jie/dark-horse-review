package com.hmdp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiAuthFilter extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "X-AI-Token";

    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public AiAuthFilter(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/ai/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = trimToEmpty(properties.getAccessToken());
        if (expected.isEmpty()) {
            reject(response, 503, "AI_DISABLED", "AI 助手未启用，请在后端设置 AI_ACCESS_TOKEN");
            return;
        }
        String provided = trimToEmpty(request.getHeader(TOKEN_HEADER));
        // 定长哈希比较，避免按字节短路泄露令牌前缀。
        boolean matched = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            reject(response, 401, "UNAUTHORIZED", "访问令牌无效");
            return;
        }
        chain.doFilter(request, response);
    }

    private String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
