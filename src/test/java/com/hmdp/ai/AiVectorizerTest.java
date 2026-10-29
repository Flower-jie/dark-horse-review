package com.hmdp.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVectorizerTest {

    private final AiVectorizer vectorizer = new AiVectorizer();

    @Test
    void producesNormalizedTwoHundredFiftySixDimensionVector() {
        float[] vector = vectorizer.vectorize("HashMap 扩容 链表 红黑树");

        assertEquals(AiVectorizer.DIM, vector.length);
        double norm = 0.0d;
        for (float value : vector) {
            norm += value * value;
        }
        assertEquals(1.0d, norm, 0.0001d);
    }

    @Test
    void blankTextYieldsZeroVector() {
        float[] vector = vectorizer.vectorize("   \r\n ");

        for (float value : vector) {
            assertEquals(0.0f, value);
        }
        assertEquals(0.0d, vectorizer.cosine(vector, vector), 0.0001d);
        assertEquals(0.0d, vectorizer.cosine(null, vector), 0.0001d);
    }

    @Test
    void identicalTextsAreMostSimilar() {
        float[] left = vectorizer.vectorize("线程池 核心线程 最大线程数 队列 拒绝策略");
        float[] right = vectorizer.vectorize("线程池 核心线程 最大线程数 队列 拒绝策略");

        assertEquals(1.0d, vectorizer.cosine(left, right), 0.0001d);
    }

    @Test
    void relatedDocumentRanksAboveUnrelatedOne() {
        float[] query = vectorizer.vectorize("线程池的核心参数有哪些");
        float[] threadPool = vectorizer.vectorize("线程池 核心参数 corePoolSize maximumPoolSize 队列 拒绝策略");
        float[] redis = vectorizer.vectorize("Redis 缓存穿透 缓存雪崩 布隆过滤器");

        double related = vectorizer.cosine(query, threadPool);
        double unrelated = vectorizer.cosine(query, redis);

        assertTrue(related > unrelated, "相关问题得分应更高");
        assertTrue(related > 0.0d);
    }

    @Test
    void handlesSurrogatePairsAndCrlfWithoutChangingTerms() {
        float[] crlf = vectorizer.vectorize("表情\uD83D\uDE00测试\r\n线程池");
        float[] lf = vectorizer.vectorize("表情\uD83D\uDE00测试\n线程池");

        assertArrayEquals(lf, crlf);
        assertEquals(1.0d, vectorizer.cosine(crlf, lf), 0.0001d);
    }

    @Test
    void chineseBigramsAndAsciiWordsAreBothIndexed() {
        float[] chineseOnly = vectorizer.vectorize("线程池");
        float[] withAscii = vectorizer.vectorize("线程池 thread_pool");

        assertTrue(vectorizer.cosine(chineseOnly, withAscii) > 0.0d);
        assertTrue(vectorizer.cosine(chineseOnly, withAscii) < 1.0d);
    }
}
