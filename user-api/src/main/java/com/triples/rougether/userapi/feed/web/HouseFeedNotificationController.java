package com.triples.rougether.userapi.feed.web;

import com.triples.rougether.userapi.feed.dto.HouseFeedNotificationRequest;
import com.triples.rougether.userapi.feed.dto.HouseFeedNotificationResponse;
import com.triples.rougether.userapi.feed.service.HouseFeedNotificationService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Feed", description = "자유게시판·인증게시판 공개 피드")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/houses/{houseId}/feed/notification")
public class HouseFeedNotificationController {
    private final HouseFeedNotificationService notifications;

    @Operation(summary = "집 피드 새 글 푸시 설정 조회", description = "이 집에 새 글이 올라올 때 받을 푸시를 자유글·인증글로 나눠 조회합니다. 한 번도 바꾸지 않았으면 기본값(자유글 true, 인증글 false)입니다. 그 집 구성원만 조회할 수 있습니다(구성원이 아니면 HOUSE_NOT_MEMBER, 없는 집이면 HOUSE_NOT_FOUND).")
    @GetMapping
    public HouseFeedNotificationResponse get(@CurrentUser AuthUser user, @PathVariable @Positive Long houseId) {
        return notifications.get(user.id(), houseId);
    }

    @Operation(summary = "집 피드 새 글 푸시 설정 변경", description = "freePostPush·verificationPostPush 중 보낸 값만 바꾸고 생략한 값은 유지합니다. 둘 다 생략하면 FEED_INPUT_INVALID입니다. 집 채팅 음소거와는 따로 저장합니다.")
    @PutMapping
    public HouseFeedNotificationResponse change(@CurrentUser AuthUser user, @PathVariable @Positive Long houseId,
                                                @RequestBody HouseFeedNotificationRequest request) {
        return notifications.change(user.id(), houseId, request);
    }
}
