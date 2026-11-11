package com.triples.rougether.infra.fcm;

import java.util.List;
import java.util.Map;

public interface FcmSender {

    // data 에 이 키가 있으면 같은 값끼리 한 묶음으로 표시함(집 채팅 #526). Android 는 같은 tag 의 앞 알림을
    // 새 알림이 대체하고, iOS 는 같은 thread-id 로 알림 센터에서 한 스택이 됨. 값은 앱에도 data 로 그대로 전달됨.
    String GROUP_KEY = "groupKey";

    FcmSendResult send(List<String> tokens, String title, String body);

    // 부가 데이터가 필요 없는 기존 발송자·stub은 기존 계약으로 처리함.
    default FcmSendResult send(List<String> tokens, String title, String body, Map<String, String> data) {
        return send(tokens, title, body);
    }
}
