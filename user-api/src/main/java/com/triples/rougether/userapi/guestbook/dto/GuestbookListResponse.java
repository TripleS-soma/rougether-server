package com.triples.rougether.userapi.guestbook.dto;

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

    public record GuestbookItem(
            @Schema(description = "방명록 ID", example = "12")
            Long guestbookId,
            @Schema(description = "작성자 회원 ID", example = "7")
            Long authorId,
            @Schema(description = "작성자 닉네임 (온보딩 전이면 null)", example = "진형")
            String authorNickname,
            @Schema(description = "방명록 내용", example = "오늘도 루틴 완료! 방 예쁘다 ㅎㅎ")
            String content,
            @Schema(description = "작성 시각. 목록은 최신순(guestbookId 내림차순) 정렬")
            Instant createdAt) {

        public static GuestbookItem of(RoomGuestbook guestbook) {
            return new GuestbookItem(
                    guestbook.getId(),
                    guestbook.getAuthor().getId(),
                    guestbook.getAuthor().getNickname(),
                    guestbook.getContent(),
                    guestbook.getCreatedAt());
        }
    }
}
