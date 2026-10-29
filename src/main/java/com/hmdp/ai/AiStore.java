package com.hmdp.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Repository
public class AiStore {

    private static final Logger log = LoggerFactory.getLogger(AiStore.class);

    static final int MAX_TITLE = 100;
    static final int MAX_SOURCE = 160;
    static final int MAX_CONTENT = 20000;
    static final int MAX_CHUNKS_PER_DOC = 30;
    static final int MAX_CHUNKS_TOTAL = 5000;

    private static final String CREATE_SQL = "CREATE TABLE IF NOT EXISTS ai_chunk ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
            + "source VARCHAR(160) NOT NULL, "
            + "title VARCHAR(200) NOT NULL, "
            + "ordinal INT NOT NULL, "
            + "content VARCHAR(2000) NOT NULL, "
            + "vector VARCHAR(4000) NOT NULL)";

    private static final String INSERT_SQL =
            "INSERT INTO ai_chunk (source, title, ordinal, content, vector) VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_ALL_SQL =
            "SELECT source, title, ordinal, content, vector FROM ai_chunk";

    private final JdbcTemplate jdbcTemplate;
    private final AiChunker chunker;
    private final AiVectorizer vectorizer;
    private final ReentrantLock writeLock = new ReentrantLock();

    private volatile boolean ready;
    private volatile RuntimeException failure;

    public AiStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.chunker = new AiChunker();
        this.vectorizer = new AiVectorizer();
    }

    public Stats status() {
        ensure();
        Integer chunks = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_chunk", Integer.class);
        Integer sources = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT source) FROM ai_chunk", Integer.class);
        return new Stats(chunks == null ? 0 : chunks, sources == null ? 0 : sources);
    }

    public List<Map<String, Object>> listSources() {
        ensure();
        return jdbcTemplate.query(
                "SELECT source, MIN(title) AS title, COUNT(*) AS chunk_total FROM ai_chunk GROUP BY source ORDER BY source",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("source", rs.getString("source"));
                    item.put("title", rs.getString("title"));
                    item.put("chunkCount", rs.getInt("chunk_total"));
                    return item;
                });
    }

    public int add(String title, String source, String content) {
        String safeTitle = AiChunker.strip(title == null ? "" : title);
        String safeSource = normalizeSource(source, safeTitle);
        String safeContent = AiChunker.strip(content == null ? "" : content);
        if (safeTitle.isEmpty() || safeTitle.length() > MAX_TITLE) {
            throw new ValidationException("标题不能为空且不超过 " + MAX_TITLE + " 字");
        }
        if (safeSource.length() > MAX_SOURCE) {
            throw new ValidationException("资料标识不能超过 " + MAX_SOURCE + " 个字符");
        }
        if (safeContent.isEmpty() || safeContent.length() > MAX_CONTENT) {
            throw new ValidationException("内容不能为空且不超过 " + MAX_CONTENT + " 字");
        }
        List<String> chunks = chunker.split(safeContent);
        if (chunks.isEmpty()) {
            throw new ValidationException("内容不能为空且不超过 " + MAX_CONTENT + " 字");
        }
        if (chunks.size() > MAX_CHUNKS_PER_DOC) {
            throw new ValidationException("单篇资料最多 " + MAX_CHUNKS_PER_DOC + " 个片段，请拆分后导入");
        }
        ensure();
        writeLock.lock();
        try {
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_chunk WHERE source = ?", Integer.class, safeSource);
            if (existing != null && existing > 0) {
                throw new ConflictException("资料标识已存在：" + safeSource);
            }
            Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_chunk", Integer.class);
            int current = total == null ? 0 : total;
            if (current + chunks.size() > MAX_CHUNKS_TOTAL) {
                throw new ValidationException("知识库容量已达上限（" + MAX_CHUNKS_TOTAL + " 个片段）");
            }
            insert(safeSource, safeTitle, chunks);
            return chunks.size();
        } finally {
            writeLock.unlock();
        }
    }

    public int delete(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new ValidationException("资料标识不能为空");
        }
        ensure();
        writeLock.lock();
        try {
            return jdbcTemplate.update("DELETE FROM ai_chunk WHERE source = ?", source.trim());
        } finally {
            writeLock.unlock();
        }
    }

    public List<Match> search(String query, int topK) {
        if (query == null || query.trim().isEmpty() || topK <= 0) {
            return Collections.emptyList();
        }
        ensure();
        float[] queryVector = vectorizer.vectorize(query);
        List<Match> matches = new ArrayList<Match>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(SELECT_ALL_SQL)) {
            float[] stored = decode((String) row.get("vector"));
            double score = vectorizer.cosine(queryVector, stored);
            if (score > 0.0d) {
                String content = (String) row.get("content");
                matches.add(new Match((String) row.get("title"), (String) row.get("source"),
                        ((Number) row.get("ordinal")).intValue(), score, content));
            }
        }
        Collections.sort(matches, new Comparator<Match>() {
            @Override
            public int compare(Match left, Match right) {
                int byScore = Double.compare(right.getScore(), left.getScore());
                if (byScore != 0) {
                    return byScore;
                }
                int bySource = left.getSource().compareTo(right.getSource());
                return bySource != 0 ? bySource : Integer.compare(left.getOrdinal(), right.getOrdinal());
            }
        });
        if (matches.size() > topK) {
            return new ArrayList<Match>(matches.subList(0, topK));
        }
        return matches;
    }

    private void ensure() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            if (failure != null) {
                throw failure;
            }
            try {
                jdbcTemplate.execute(CREATE_SQL);
                Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_chunk", Integer.class);
                if (count == null || count == 0) {
                    loadSeeds();
                }
                ready = true;
            } catch (RuntimeException e) {
                // 保留首次失败的堆栈，后续请求直接 503，而不是反复重试拖慢响应。
                log.error("AI 知识库初始化失败", e);
                failure = new UnavailableException("知识库暂时不可用，请稍后重试", e);
                throw failure;
            }
        }
    }

    private void loadSeeds() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:ai/*.md");
            Arrays.sort(resources, new Comparator<Resource>() {
                @Override
                public int compare(Resource left, Resource right) {
                    return fileName(left).compareTo(fileName(right));
                }
            });
            for (Resource resource : resources) {
                String text;
                try (InputStream in = resource.getInputStream()) {
                    text = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
                }
                List<String> chunks = chunker.split(text);
                if (chunks.isEmpty()) {
                    continue;
                }
                insert(fileName(resource), titleOf(text, fileName(resource)), chunks);
            }
            log.info("AI 知识库已加载 {} 篇种子资料", resources.length);
        } catch (Exception e) {
            throw new IllegalStateException("加载种子资料失败", e);
        }
    }

    private void insert(String source, String title, List<String> chunks) {
        List<Object[]> rows = new ArrayList<Object[]>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            rows.add(new Object[]{source, title, i, content, encode(vectorizer.vectorize(content))});
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, rows);
    }

    private String normalizeSource(String source, String title) {
        String value = source == null ? "" : source.trim();
        return value.isEmpty() ? title : value;
    }

    private static String fileName(Resource resource) {
        String name = resource.getFilename();
        if (name == null) {
            return "unknown";
        }
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static String titleOf(String text, String fallback) {
        String[] lines = text.split("\n", 5);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).trim();
                if (!title.isEmpty()) {
                    return title.length() > 200 ? title.substring(0, 200) : title;
                }
            }
        }
        return fallback;
    }

    static String encode(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Math.round(vector[i] * 10000.0f) / 10000.0f);
        }
        return builder.toString();
    }

    static float[] decode(String value) {
        float[] vector = new float[AiVectorizer.DIM];
        if (value == null || value.isEmpty()) {
            return vector;
        }
        String[] parts = value.split(",");
        int size = Math.min(parts.length, vector.length);
        for (int i = 0; i < size; i++) {
            try {
                vector[i] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException e) {
                vector[i] = 0.0f;
            }
        }
        return vector;
    }

    public static final class Stats {
        private final int chunkCount;
        private final int sourceCount;

        Stats(int chunkCount, int sourceCount) {
            this.chunkCount = chunkCount;
            this.sourceCount = sourceCount;
        }

        public int getChunkCount() {
            return chunkCount;
        }

        public int getSourceCount() {
            return sourceCount;
        }
    }

    public static final class Match {
        private final String title;
        private final String source;
        private final int ordinal;
        private final double score;
        private final String text;

        Match(String title, String source, int ordinal, double score, String text) {
            this.title = title;
            this.source = source;
            this.ordinal = ordinal;
            this.score = score;
            this.text = text;
        }

        public String getTitle() {
            return title;
        }

        public String getSource() {
            return source;
        }

        public int getOrdinal() {
            return ordinal;
        }

        public double getScore() {
            return score;
        }

        public String getText() {
            return text;
        }
    }

    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
