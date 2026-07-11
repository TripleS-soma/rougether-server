package com.triples.rougether.userapi.notification.fcm;

import java.util.List;

public interface FcmSender {

    // 전달받은 token 전체로 멀티캐스트 발송함. 반환값은 FCM이 UNREGISTERED/INVALID_ARGUMENT로 응답한 무효 token 목록(삭제 대상).
    List<String> send(List<String> tokens, String title, String body);
}
