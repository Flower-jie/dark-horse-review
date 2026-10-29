package com.hmdp.ai;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 词项哈希向量：只统计字面 term 的共现，不包含语义信息，因此相似度仅代表字面重叠程度。
 */
public class AiVectorizer {

    public static final int DIM = 256;

    private static final Pattern TERM = Pattern.compile("[\\u4e00-\\u9fff]+|[a-z0-9_]+");
    private static final int FNV_OFFSET = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    public float[] vectorize(String text) {
        float[] vector = new float[DIM];
        if (text == null) {
            return vector;
        }
        Matcher matcher = TERM.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String run = matcher.group();
            if (isChinese(run.charAt(0))) {
                if (run.length() == 1) {
                    add(vector, run);
                } else {
                    for (int i = 0; i + 1 < run.length(); i++) {
                        add(vector, run.substring(i, i + 2));
                    }
                }
            } else {
                add(vector, run);
            }
        }
        double norm = 0.0d;
        for (int i = 0; i < DIM; i++) {
            norm += (double) vector[i] * vector[i];
        }
        if (norm > 0.0d) {
            float scale = (float) (1.0d / Math.sqrt(norm));
            for (int i = 0; i < DIM; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    public double cosine(float[] a, float[] b) {
        if (a == null || b == null) {
            return 0.0d;
        }
        int size = Math.min(a.length, b.length);
        double dot = 0.0d;
        for (int i = 0; i < size; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    private void add(float[] vector, String term) {
        int hash = fnv1a(term);
        int index = hash & (DIM - 1);
        float sign = ((hash >>> 16) & 1) == 0 ? 1.0f : -1.0f;
        vector[index] += sign;
    }

    static int fnv1a(String term) {
        int hash = FNV_OFFSET;
        for (int i = 0; i < term.length(); i++) {
            hash ^= term.charAt(i) & 0xff;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }
}
