package com.triples.rougether.userapi.guestbook.dto;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.room.entity.RoomGuestbook;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

// GET /api/v1/rooms/{roomOwnerId}/guestbooks 응답. 커서 기반 무한스크롤(최신순).
public record GuestbookListResponse(
        List<GuestbookItem> items,
        @Schema(description = "다음 페이지 커서 (다음 요청의 cursor 로 전달, 더 없으면 null)", example = "12")
        Long nextCursor,
        @Schema(description = "다음 페이지 존재 여부", example = "true")
        boolean hasNext) {

    // 탈퇴자가 남긴 글은 지우지 않고 작성자 표시만 가린다 - 남은 사람의 방 이력에 구멍을 내지 않기 위함.
    // 탈퇴 시 nickname 이 null 로 비워지므로 "온보딩 전(null)"과 구분하려면 deletedAt 을 봐야 한다.
    private static final String WITHDRAWN_AUTHOR_NICKNAME = "탈퇴한 사용자";

    public record GuestbookItem(
            @Schema(description = "방명록 ID", example = "12")
            Long guestbookId,
            @Schema(description = "작성자 회원 ID", example = "7")
            Long authorId,
            @Schema(description = "작성자 닉네임 (온보딩 전이면 null, 탈퇴한 회원이면 \"탈퇴한 사용자\")", example = "진형")
            String authorNickname,
            @Schema(description = "방명록 내용", example = "오늘도 루틴 완료! 방 예쁘다 ㅎㅎ")
            String content,
            @Schema(description = "작성 시각. 목록은 최신순(guestbookId 내림차순) 정렬")
            Instant createdAt) {

        public static GuestbookItem of(RoomGuestbook guestbook) {
            User author = guestbook.getAuthor();
            return new GuestbookItem(
                    guestbook.getId(),
                    author.getId(),
                    author.isWithdrawn() ? WITHDRAWN_AUTHOR_NICKNAME : author.getNickname(),
                    guestbook.getContent(),
                    guestbook.getCreatedAt());
        }
    }
}
