package com.triples.rougether.userapi.furniture.ai;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import java.util.List;

public interface FurnitureAiClient {
    enum Decision { ACCEPT, EDIT, REGENERATE, REJECT }
    record Context(byte[] source, List<byte[]> references, byte[] candidate,
                   String targetHint, String feedback, String correction) { }
    record Generated(byte[] image, long inputTokens, long outputTokens) { }
    record Review(Decision decision, String name, String reason, String correction,
                  long inputTokens, long outputTokens) { }

    boolean available();
    Generated generate(Context context, Action action);
    Review review(Context context, List<String> hardFailures);
}
