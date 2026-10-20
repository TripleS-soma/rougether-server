package com.triples.rougether.infra.fcm;

import java.util.List;

// 실패 사유와 예외를 보존해 호출자가 기존 로그 수준과 문맥을 유지하도록 함
public record FcmDeliveryResult(Status status, List<String> invalidTokens, Exception cause) {

    public enum Status {
        SENT,
        NO_TOKENS,
        SEND_FAILED,
        NO_SUCCESS
    }

    public boolean successful() {
        return status == Status.SENT;
    }

    static FcmDeliveryResult noTokens() {
        return new FcmDeliveryResult(Status.NO_TOKENS, List.of(), null);
    }

    static FcmDeliveryResult failed(Exception cause) {
        return new FcmDeliveryResult(Status.SEND_FAILED, List.of(), cause);
    }

    static FcmDeliveryResult from(FcmSendResult result) {
        Status status = result.successCount() > 0 ? Status.SENT : Status.NO_SUCCESS;
        return new FcmDeliveryResult(status, result.invalidTokens(), null);
    }
}
