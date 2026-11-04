package com.triples.rougether.batch.moderation;

// 401·403. 키 오류·권한 문제라 재시도해도 소용없음 — 그 실행을 중단하고 운영 알림을 보냄.
public class JevAuthException extends JevException {

    public JevAuthException(String message, Throwable cause) {
        super(message, false, cause);
    }
}
