package com.triples.rougether.batch.alert;

// batch 운영 실패 알림. 구현은 전송 실패를 삼켜야 하며 배치 흐름을 막으면 안 됨
public interface BatchFailureAlertNotifier {

    // 같은 key 는 cooldown 동안 한 번만 알림
    void notifyFailure(String key, String title, String detail);
}
