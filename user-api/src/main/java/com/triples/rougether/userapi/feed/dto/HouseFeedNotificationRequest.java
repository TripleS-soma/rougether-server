package com.triples.rougether.userapi.feed.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "집 피드 새 글 푸시 설정 변경. 생략한 필드는 유지하며 둘 다 생략할 수는 없음")
public record HouseFeedNotificationRequest(
        @Schema(description = "이 집의 자유글 새 글 푸시를 받을지", nullable = true) Boolean freePostPush,
        @Schema(description = "이 집의 인증글 새 글 푸시를 받을지", nullable = true) Boolean verificationPostPush) {
}
