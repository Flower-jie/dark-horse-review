package com.hmdp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AiIntegrationTest.AiTestApplication.class)
@AutoConfigureMockMvc
class AiIntegrationTest {

    private static final String TOKEN = "test-token-0123456789abcdef";
    private static final String TOKEN_HEADER = "X-AI-Token";

    private static final AtomicReference<String> MODEL_REQUEST = new AtomicReference<String>();
    private static final AtomicReference<String> MODEL_AUTH = new AtomicReference<String>();

    private static HttpServer modelServer;
    private static String okBaseUrl;
    private static String failingBaseUrl;

    @SpringBootApplication(scanBasePackages = "com.hmdp.ai")
    @EnableAutoConfiguration
    static class AiTestApplication {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        startModelServer();
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:aitest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.redis.password", () -> "");
        registry.add("app.ai.access-token", () -> TOKEN);
        registry.add("app.ai.chat-base-url", () -> okBaseUrl);
        registry.add("app.ai.chat-api-key", () -> "test-key");
        registry.add("app.ai.chat-model", () -> "test-model");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiProperties aiProperties;

    private String savedBaseUrl;
    private String savedModel;
    private String savedToken;

    @BeforeEach
    void rememberProperties() {
        savedBaseUrl = aiProperties.getChatBaseUrl();
        savedModel = aiProperties.getChatModel();
        savedToken = aiProperties.getAccessToken();
        MODEL_REQUEST.set(null);
        MODEL_AUTH.set(null);
    }

    @AfterEach
    void restoreProperties() {
        aiProperties.setChatBaseUrl(savedBaseUrl);
        aiProperties.setChatModel(savedModel);
        aiProperties.setAccessToken(savedToken);
    }

    @AfterAll
    static void stopModelServer() {
        if (modelServer != null) {
            modelServer.stop(0);
            modelServer = null;
        }
    }

    @Test
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/ai/api/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("访问令牌无效"));
    }

    @Test
    void rejectsWrongToken() throws Exception {
        mockMvc.perform(get("/ai/api/status").header(TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void reportsDisabledWhenTokenIsNotConfigured() throws Exception {
        aiProperties.setAccessToken("");

        mockMvc.perform(get("/ai/api/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_DISABLED"))
                .andExpect(jsonPath("$.message").value("AI 助手未启用，请在后端设置 AI_ACCESS_TOKEN"));
    }

    @Test
    void leavesNonAiPathsUntouched() throws Exception {
        mockMvc.perform(get("/user/me")).andExpect(status().isNotFound());
    }

    @Test
    void servesStaticPageWithoutToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/ai/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();
        String html = bodyOf(result);
        assertTrue(html.contains("黑马点评 · Java 知识助手"));
        assertTrue(html.contains("仅检索原文"));
    }

    @Test
    void redirectsFriendlyAiPath() throws Exception {
        mockMvc.perform(get("/ai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ai/index.html"));
        mockMvc.perform(get("/ai/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ai/index.html"));
    }

    @Test
    void statusShowsLocalHashModeAndSeedChunks() throws Exception {
        mockMvc.perform(get("/ai/api/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievalMode").value("local_hash"))
                .andExpect(jsonPath("$.chatEnabled").value(true))
                .andExpect(jsonPath("$.chunkCount").isNumber());

        MvcResult result = mockMvc.perform(get("/ai/api/status").header(TOKEN_HEADER, TOKEN))
                .andReturn();
        JsonNode status = objectMapper.readTree(bodyOf(result));
        assertTrue(status.path("chunkCount").asInt() > 0, "种子资料应已入库");
    }

    @Test
    void listsSeedSources() throws Exception {
        MvcResult result = mockMvc.perform(get("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(bodyOf(result));
        assertTrue(list.isArray());
        List<String> sources = new ArrayList<String>();
        for (JsonNode item : list) {
            sources.add(item.path("source").asText());
            assertTrue(item.path("chunkCount").asInt() > 0);
        }
        assertTrue(sources.contains("java-collections"), sources.toString());
        assertTrue(sources.contains("spring-mvc-mybatis"), sources.toString());
    }

    @Test
    void importsDocumentAndRanksItFirstForRelatedQuery() throws Exception {
        String source = "zebra-quantum-coffee";
        String body = "量子咖啡的萃取参数：水温九十二度，粉水比一比十五，研磨度中细，总萃取时间两分半。"
                + "量子咖啡的萃取参数需要根据豆子烘焙度调整，量子咖啡偏酸时可以调高水温，"
                + "萃取参数中的粉水比是影响浓度的关键。";
        mockMvc.perform(post("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(map("title", "Zebra 量子咖啡冲煮手册", "content", body, "source", source))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(source))
                .andExpect(jsonPath("$.chunkCount").value(1));

        mockMvc.perform(post("/ai/api/search").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(map("query", "量子咖啡萃取参数", "topK", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrievalMode").value("local_hash"))
                .andExpect(jsonPath("$.citations[0].source").value(source))
                .andExpect(jsonPath("$.citations[0].score").isNumber())
                .andExpect(jsonPath("$.citations[0].text").isNotEmpty());
    }

    @Test
    void rejectsDuplicateSource() throws Exception {
        String source = "duplicate-source-doc";
        String body = "重复来源测试文档，内容包含唯一词项 蓝鳍金枪鱼索引。";
        String payload = json(map("title", "重复来源测试", "content", body, "source", source));
        try {
            mockMvc.perform(post("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SOURCE_EXISTS"));
        } finally {
            remove(source);
        }
    }

    @Test
    void rejectsOversizedDocument() throws Exception {
        StringBuilder huge = new StringBuilder(20001);
        for (int i = 0; i < 20001; i++) {
            huge.append('x');
        }
        mockMvc.perform(post("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(map("title", "超长文档", "content", huge.toString(), "source", "too-long-doc"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/ai/api/chat").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(map("question", "   "))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void deleteRemovesDocumentFromSearch() throws Exception {
        String source = "whale-scheduler";
        mockMvc.perform(post("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(map("title", "蓝鲸调度器说明", "source", source,
                        "content", "蓝鲸调度器负责分片调度，蓝鲸调度器的心跳间隔是三十秒。"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/ai/api/search").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(map("query", "蓝鲸调度器", "topK", 5))))
                .andExpect(jsonPath("$.citations[0].source").value(source));

        mockMvc.perform(delete("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN)
                .param("source", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        MvcResult result = mockMvc.perform(post("/ai/api/search").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(map("query", "蓝鲸调度器", "topK", 5))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode citations = objectMapper.readTree(bodyOf(result)).path("citations");
        for (JsonNode citation : citations) {
            assertFalse(source.equals(citation.path("source").asText()), "删除后不应再检索到该资料");
        }
    }

    @Test
    void treatsUnsafeModelUrlAsNotConfigured() throws Exception {
        aiProperties.setChatBaseUrl("http://example.com/v1");
        aiProperties.setChatModel("test-model");

        mockMvc.perform(get("/ai/api/status").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatEnabled").value(false));

        List<String[]> events = chat("HashMap 的扩容条件是什么？");

        assertTrue(dataOf(events, "delta").isEmpty());
        assertFalse(dataOf(events, "notice").isEmpty());
        assertFalse(dataOf(events, "error").size() > 0);
    }

    @Test
    void chatWithoutModelReturnsMetaCitationsNoticeAndDone() throws Exception {
        aiProperties.setChatBaseUrl("");
        aiProperties.setChatModel("");

        List<String[]> events = chat("HashMap 的扩容和红黑树转换条件是什么？");

        List<String[]> names = events;
        List<String> delta = dataOf(names, "delta");
        assertTrue(delta.isEmpty(), "未配置模型时不应产生 delta");
        assertFalse(dataOf(names, "meta").isEmpty(), "应先发送 meta");
        JsonNode meta = objectMapper.readTree(dataOf(names, "meta").get(0));
        assertFalse(meta.path("chatEnabled").asBoolean());
        assertTrue(meta.path("citations").size() > 0, "应检索到种子资料");
        List<String> notices = dataOf(names, "notice");
        assertFalse(notices.isEmpty());
        assertTrue(objectMapper.readTree(notices.get(0)).path("message").asText().contains("未配置生成模型"));
        assertEquals("{}", dataOf(names, "done").get(dataOf(names, "done").size() - 1));
        assertEquals("meta", names.get(0)[0]);
        assertEquals("done", names.get(names.size() - 1)[0]);
    }

    @Test
    void chatWithModelStreamsDeltasWithCitationMarkers() throws Exception {
        aiProperties.setChatBaseUrl(okBaseUrl);
        aiProperties.setChatModel("test-model");

        List<String[]> events = chat("线程池的核心参数有哪些？");

        List<String> metaList = dataOf(events, "meta");
        assertFalse(metaList.isEmpty());
        JsonNode meta = objectMapper.readTree(metaList.get(0));
        assertTrue(meta.path("chatEnabled").asBoolean());
        assertTrue(meta.path("citations").size() > 0);

        List<String> deltas = dataOf(events, "delta");
        assertTrue(deltas.size() >= 2, "应分多次推送 delta");
        StringBuilder answer = new StringBuilder();
        for (String item : deltas) {
            answer.append(objectMapper.readTree(item).path("text").asText());
        }
        assertTrue(answer.toString().contains("[1]"), answer.toString());
        assertTrue(answer.toString().contains("maximumPoolSize"));
        assertFalse(dataOf(events, "error").size() > 0);
        assertEquals("done", events.get(events.size() - 1)[0]);

        String sent = MODEL_REQUEST.get();
        assertNotNull(sent);
        JsonNode request = objectMapper.readTree(sent);
        assertTrue(request.path("stream").asBoolean());
        assertEquals(0.2d, request.path("temperature").asDouble(), 0.0001d);
        assertEquals("test-model", request.path("model").asText());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertTrue(request.path("messages").get(0).path("content").asText().contains("只用"));
        String user = request.path("messages").get(1).path("content").asText();
        assertTrue(user.contains("[1]"));
        assertTrue(user.contains("问题：线程池的核心参数有哪些？"));
        assertEquals("Bearer test-key", MODEL_AUTH.get());
    }

    @Test
    void chatWithFailingModelReportsErrorWithoutFabricatingAnswer() throws Exception {
        aiProperties.setChatBaseUrl(failingBaseUrl);
        aiProperties.setChatModel("test-model");

        List<String[]> events = chat("线程池的核心参数有哪些？");

        assertTrue(dataOf(events, "delta").isEmpty(), "失败时不得输出伪造内容");
        List<String> errors = dataOf(events, "error");
        assertFalse(errors.isEmpty());
        assertTrue(objectMapper.readTree(errors.get(0)).path("message").asText().contains("模型服务暂时不可用"));
        assertFalse(dataOf(events, "meta").isEmpty(), "引用仍然有效");
        assertEquals("done", events.get(events.size() - 1)[0]);
    }

    private List<String[]> chat(String question) throws Exception {
        MvcResult result = mockMvc.perform(post("/ai/api/chat").header(TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content(json(map("question", question))))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(30000);
        }
        return parseEvents(bodyOf(result));
    }

    private List<String[]> parseEvents(String body) {
        List<String[]> events = new ArrayList<String[]>();
        String event = "message";
        String[] lines = body.split("\n");
        for (String line : lines) {
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                events.add(new String[]{event, line.substring("data:".length())});
            }
        }
        return events;
    }

    private List<String> dataOf(List<String[]> events, String name) {
        List<String> data = new ArrayList<String>();
        for (String[] item : events) {
            if (name.equals(item[0])) {
                data.add(item[1]);
            }
        }
        return data;
    }

    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private void remove(String source) throws Exception {
        mockMvc.perform(delete("/ai/api/knowledge").header(TOKEN_HEADER, TOKEN).param("source", source));
    }

    private static synchronized void startModelServer() {
        if (modelServer != null) {
            return;
        }
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            modelServer = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
            modelServer.createContext("/v1/chat/completions", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    handleCompletion(exchange);
                }
            });
            modelServer.createContext("/fail/chat/completions", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    readAll(exchange.getRequestBody());
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });
            modelServer.setExecutor(Executors.newFixedThreadPool(4));
            modelServer.start();
            int port = modelServer.getAddress().getPort();
            okBaseUrl = "http://127.0.0.1:" + port + "/v1";
            failingBaseUrl = "http://127.0.0.1:" + port + "/fail";
        } catch (IOException e) {
            throw new IllegalStateException("无法启动模拟模型服务", e);
        }
    }

    private static void handleCompletion(HttpExchange exchange) throws IOException {
        MODEL_REQUEST.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
        MODEL_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            write(out, ": keep-alive\r\n\r\n");
            write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"线程池的核心参数包括 corePoolSize、maximumPoolSize\"}}]}\r\n\r\n");
            write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"、workQueue、threadFactory、handler\"}}]}\r\n\r\n");
            write(out, "event: message\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"，提交顺序是先核心线程再队列最后最大线程 [1]。\"}}]}\r\n\r\n");
            write(out, "data: [DONE]\r\n\r\n");
        } finally {
            exchange.close();
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
