package com.triples.rougether.userapi.moderation.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.moderation.dto.BlockedUserPageResponse;
import com.triples.rougether.userapi.moderation.service.UserBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 사용자 차단(#399). 한 방향이며 차단한 사람의 화면에만 적용. App Store 1.2 차단 요건.
@Tag(name = "Block", description = "사용자 차단 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UserBlockController {

    private final UserBlockService blocks;

    @Operation(summary = "사용자 차단",
            description = "차단하면 내 피드·작성자별 목록에서 상대 게시물이 빠지고, 상대 게시물 상세·댓글·좋아요는 FEED_POST_NOT_FOUND(404), "
                    + "다른 글의 댓글 목록과 commentCount 에서 상대 댓글이 빠지며, 상대 댓글 알림을 받지 않고, 거래소 종목 목록에서 상대가 만든 가구가 빠집니다. "
                    + "한 방향이라 상대 화면은 바뀌지 않고 상대에게 알리지 않습니다. 이미 차단했어도 204. "
                    + "나 자신은 BLOCK_SELF(400), 없거나 탈퇴·봇 계정은 USER_NOT_FOUND(404).")
    @PutMapping("/users/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@CurrentUser AuthUser user,
                      @Parameter(description = "차단할 회원 ID") @PathVariable @Positive Long userId) {
        blocks.block(user.id(), userId);
    }

    @Operation(summary = "사용자 차단 해제",
            description = "즉시 상대 콘텐츠가 다시 보입니다. 차단하지 않았거나 상대가 탈퇴했어도 204. 나 자신은 BLOCK_SELF(400).")
    @DeleteMapping("/users/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@CurrentUser AuthUser user,
                        @Parameter(description = "차단 해제할 회원 ID") @PathVariable @Positive Long userId) {
        blocks.unblock(user.id(), userId);
    }

    @Operation(summary = "내가 차단한 사용자 목록",
            description = "최근에 차단한 순서입니다. 피드와 같은 {items, nextCursor, hasNext} 형식이며 다음 페이지는 nextCursor 를 cursor 로 그대로 전달합니다"
                    + "(차단 기록 ID라 userId 와 다름).")
    @GetMapping("/me/blocks")
    public BlockedUserPageResponse list(@CurrentUser AuthUser user,
            @Parameter(description = "이전 응답의 nextCursor. 첫 페이지는 생략") @RequestParam(required = false) @Positive Long cursor,
            @Parameter(description = "페이지 크기 (1~50)") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return blocks.list(user.id(), cursor, size);
    }
}
