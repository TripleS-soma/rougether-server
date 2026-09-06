package com.triples.rougether.infra.fcm;

import java.util.List;
import java.util.Map;

public interface FcmSender {

    FcmSendResult send(List<String> tokens, String title, String body);

    // 부가 데이터가 필요 없는 기존 발송자·stub은 기존 계약으로 처리함.
    default FcmSendResult send(List<String> tokens, String title, String body, Map<String, String> data) {
        return send(tokens, title, body);
    }
}
