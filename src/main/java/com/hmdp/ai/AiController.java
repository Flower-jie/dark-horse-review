package com.hmdp.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/ai/api")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private static final MediaType UTF8_JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final String FAILURE_EVENT =
            "{\"message\":\"模型服务暂时不可用，请稍后重试或切换到仅检索\"}";

    private final AiService aiService;
    private final ExecutorService chatExecutor;

    public AiController(AiService aiService) {
        this.aiService = aiService;
        final AtomicInteger counter = new AtomicInteger();
        this.chatExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ai-chat-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        chatExecutor.shutdownNow();
    }

    @GetMapping("/status")
    public ResponseEntity<Object> status() {
        try {
            return ResponseEntity.ok().contentType(UTF8_JSON).body(aiService.status());
        } catch (AiStore.UnavailableException e) {
            return unavailable();
        } catch (RuntimeException e) {
            return internal(e);
        }
    }

    @GetMapping("/knowledge")
    public ResponseEntity<Object> knowledge() {
        try {
            return ResponseEntity.ok().contentType(UTF8_JSON).body(aiService.knowledge());
        } catch (AiStore.UnavailableException e) {
            return unavailable();
        } catch (RuntimeException e) {
            return internal(e);
        }
    }

    @PostMapping("/knowledge")
    public ResponseEntity<Object> addKnowledge(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok().contentType(UTF8_JSON)
                    .body(aiService.importDocument(text(body, "title"), text(body, "content"), text(body, "source")));
        } catch (AiStore.ValidationException e) {
            return invalid(e.getMessage());
        } catch (AiStore.ConflictException e) {
            return error(HttpStatus.CONFLICT, "SOURCE_EXISTS", e.getMessage());
        } catch (AiStore.UnavailableException e) {
            return unavailable();
        } catch (RuntimeException e) {
            return internal(e);
        }
    }

    @DeleteMapping("/knowledge")
    public ResponseEntity<Object> deleteKnowledge(@RequestParam(value = "source", required = false) String source) {
        try {
            String value = source == null ? "" : source.trim();
            if (value.isEmpty()) {
                throw new AiStore.ValidationException("资料标识不能为空");
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("deleted", aiService.delete(value));
            return ResponseEntity.ok().contentType(UTF8_JSON).body(body);
        } catch (AiStore.ValidationException e) {
            return invalid(e.getMessage());
        } catch (AiStore.UnavailableException e) {
            return unavailable();
        } catch (RuntimeException e) {
            return internal(e);
        }
    }

    @PostMapping("/search")
    public ResponseEntity<Object> search(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Object topK = body == null ? null : body.get("topK");
            Integer limit = topK instanceof Number ? Integer.valueOf(((Number) topK).intValue()) : null;
            return ResponseEntity.ok().contentType(UTF8_JSON)
                    .body(aiService.search(text(body, "query"), limit));
        } catch (AiStore.ValidationException e) {
            return invalid(e.getMessage());
        } catch (AiStore.UnavailableException e) {
            return unavailable();
        } catch (RuntimeException e) {
            return internal(e);
        }
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chat(@RequestBody(required = false) Map<String, Object> body) {
        final String question;
        try {
            question = AiService.requireQuestion(text(body, "question"));
        } catch (AiStore.ValidationException e) {
            return invalid(e.getMessage());
        }
        final SseEmitter emitter = new SseEmitter(90000L);
        final ChatSink sink = new ChatSink(emitter);
        emitter.onCompletion(sink::markClosed);
        emitter.onTimeout(sink::markClosed);
        emitter.onError(error -> sink.markClosed());
        chatExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    aiService.chat(question, sink);
                } catch (RuntimeException e) {
                    log.warn("AI 对话异常：{}", e.getClass().getSimpleName());
                    sink.event("error", FAILURE_EVENT);
                } finally {
                    sink.complete();
                }
            }
        });
        return emitter;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Object> badRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确");
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private ResponseEntity<Object> invalid(String message) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", message);
    }

    private ResponseEntity<Object> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "知识库暂时不可用，请稍后重试");
    }

    private ResponseEntity<Object> internal(RuntimeException e) {
        log.error("AI 接口异常", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器异常");
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).contentType(UTF8_JSON).body(body);
    }

    private static final class ChatSink implements AiService.SseSink {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private ChatSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void event(String name, String json) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(json, UTF8_TEXT));
            } catch (Exception e) {
                closed.set(true);
            }
        }

        @Override
        public boolean closed() {
            return closed.get();
        }

        void markClosed() {
            closed.set(true);
        }

        void complete() {
            if (closed.getAndSet(true)) {
                return;
            }
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("SSE 连接已关闭");
            }
        }
    }
}
