package com.triples.rougether.userapi.feed.web;

import com.triples.rougether.userapi.feed.dto.FeedAuthorHouseListResponse;
import com.triples.rougether.userapi.feed.service.FeedAuthorHouseQueryService;
import com.triples.rougether.userapi.feed.service.FeedAuthorHouseCommandService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feed", description = "자유게시판·인증게시판 공개 피드")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/feed/posts/{postId}/houses")
public class FeedAuthorHouseController {
    private final FeedAuthorHouseQueryService query;
    private final FeedAuthorHouseCommandService commands;

    @Operation(summary = "게시물 작성자의 입주 대상 집 목록",
            description = "작성자가 현재 ACTIVE 구성원인 집을 공개 여부와 무관하게 최신 생성순으로 반환합니다. 방장인 집과 일반 구성원인 집 모두 포함합니다. "
                    + "삭제된 집과 작성자가 탈퇴·강퇴된 집은 제외합니다. 삭제·탈퇴·차단으로 볼 수 없는 게시물은 FEED_POST_NOT_FOUND입니다. "
                    + "requiresApproval=false면 바로 입주, true면 입주 신청 버튼을 표시하고 canJoin으로 활성 여부를 결정합니다. "
                    + "POST /api/v1/feed/posts/{postId}/houses/{houseId}/join으로 참여하며 비공개 집도 코드 없이 신청할 수 있습니다. "
                    + "바로 입주 설정이 켜진 집만 즉시 입주하며, 속한 집이 없으면 items는 빈 배열입니다.")
    @GetMapping
    public FeedAuthorHouseListResponse list(@CurrentUser AuthUser user, @PathVariable @Positive Long postId) {
        return query.list(user.id(), postId);
    }

    @Operation(summary = "게시물 작성자의 집에 참여",
            description = "공개·비공개 집 모두 코드 입력 없이 참여합니다. 기존 바로 입주 설정이 켜져 있으면 즉시 ACTIVE 구성원으로 입주하며 "
                    + "꺼져 있으면 방장 승인 대기 신청을 생성합니다(pendingApproval=true, joinRequestId 반환). "
                    + "참여 시점에도 작성자가 이 집의 ACTIVE 구성원이어야 하며, 정원·강퇴 이력을 검사합니다. "
                    + "즉시입주 시 LEFT 이력은 재활성화하고 기존 PENDING 신청은 ACCEPTED로 종결합니다. "
                    + "동거 봇만으로 만석이면 봇이 자리를 양보합니다. 이미 구성원이면 HOUSE_ALREADY_MEMBER입니다.")
    @PostMapping("/{houseId}/join")
    public HouseJoinResponse join(@CurrentUser AuthUser user, @PathVariable @Positive Long postId,
                                  @PathVariable @Positive Long houseId) {
        return commands.join(user.id(), postId, houseId);
    }
}
