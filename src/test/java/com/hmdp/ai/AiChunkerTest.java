package com.hmdp.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChunkerTest {

    private final AiChunker chunker = new AiChunker();

    @Test
    void normalizesCrlfAndCr() {
        List<String> chunks = chunker.split("第一行\r\n第二行\r第三行\n");
        assertEquals(1, chunks.size());
        assertEquals("第一行\n第二行\n第三行", chunks.get(0));
    }

    @Test
    void ignoresBlankInput() {
        assertTrue(chunker.split(null).isEmpty());
        assertTrue(chunker.split("").isEmpty());
        assertTrue(chunker.split("  \r\n\t \n ").isEmpty());
        assertTrue(chunker.split("\u3000\u3000\r\n\u3000").isEmpty());
    }

    @Test
    void splitsWithEightHundredSizeAndOneHundredOverlap() {
        String text = repeat('x', 2000);

        List<String> chunks = chunker.split(text);

        assertEquals(3, chunks.size());
        assertEquals(800, chunks.get(0).length());
        assertEquals(text.substring(0, 800), chunks.get(0));
        assertEquals(text.substring(700, 1500), chunks.get(1));
        assertEquals(text.substring(1400), chunks.get(2));
    }

    @Test
    void doesNotCutSurrogatePairAtChunkBoundary() {
        String emoji = "\uD83D\uDE00";
        String text = repeat('a', 799) + emoji + repeat('b', 500);

        List<String> chunks = chunker.split(text);

        assertEquals(2, chunks.size());
        assertEquals(799, chunks.get(0).length());
        assertEquals(emoji.charAt(0), chunks.get(1).charAt(100));
        assertEquals(emoji.charAt(1), chunks.get(1).charAt(101));
        for (String chunk : chunks) {
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)), "块尾不能是孤立高代理项");
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)), "块首不能是孤立低代理项");
        }
    }

    @Test
    void doesNotStartChunkOnLowSurrogate() {
        String emoji = "\uD83D\uDE00";
        String text = repeat('a', 699) + emoji + repeat('b', 400);

        List<String> chunks = chunker.split(text);

        assertEquals(2, chunks.size());
        assertEquals(800, chunks.get(0).length());
        assertTrue(chunks.get(0).contains(emoji));
        assertFalse(Character.isLowSurrogate(chunks.get(1).charAt(0)));
        assertEquals(400, chunks.get(1).length());
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
