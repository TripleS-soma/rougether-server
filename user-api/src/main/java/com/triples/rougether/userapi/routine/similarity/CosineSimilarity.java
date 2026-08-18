package com.triples.rougether.userapi.routine.similarity;

// 임베딩 벡터 코사인 유사도. 후보가 요청당 수십 개라 인덱스 없이 그대로 계산한다
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    // 길이가 다르거나 어느 한쪽이 0-벡터면 0.0 (NaN 이 유사도 판정에 섞이지 않게)
    public static double of(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
