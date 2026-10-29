package com.hmdp.ai;

import java.util.ArrayList;
import java.util.List;

public class AiChunker {

    public static final int CHUNK_SIZE = 800;
    public static final int OVERLAP = 100;

    public List<String> split(String raw) {
        List<String> chunks = new ArrayList<String>();
        if (raw == null) {
            return chunks;
        }
        String text = strip(raw.replace("\r\n", "\n").replace('\r', '\n'));
        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(length, start + CHUNK_SIZE);
            // 切点落在高代理项上时回退一格，避免把一个字符拆到两个块里。
            if (end < length && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            String piece = text.substring(start, end);
            if (!strip(piece).isEmpty()) {
                chunks.add(piece);
            }
            if (end >= length) {
                break;
            }
            int next = end - OVERLAP;
            if (next <= start) {
                next = end;
            }
            if (next < length && Character.isLowSurrogate(text.charAt(next))) {
                next++;
            }
            start = next;
        }
        return chunks;
    }

    static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }
}
