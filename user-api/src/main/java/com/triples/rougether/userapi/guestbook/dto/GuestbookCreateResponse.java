package com.triples.rougether.userapi.guestbook.dto;

import com.triples.rougether.domain.room.entity.RoomGuestbook;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

// 방명록 작성 결과.
public record GuestbookCreateResponse(
        @Schema(description = "방명록 ID", example = "12")
        Long guestbookId,
        @Schema(description = "방 주인 회원 ID", example = "3")
        Long roomOwnerId,
        @Schema(description = "작성자 회원 ID", example = "7")
        Long authorId,
        @Schema(description = "집 ID", example = "1")
        Long houseId,
        @Schema(description = "방명록 내용", example = "오늘도 루틴 완료! 방 예쁘다 ㅎㅎ")
        String content,
        @Schema(description = "작성 시각")
        Instant createdAt) {

    public static GuestbookCreateResponse of(RoomGuestbook guestbook) {
        return new GuestbookCreateResponse(
                guestbook.getId(),
                guestbook.getRoomOwner().getId(),
                guestbook.getAuthor().getId(),
                guestbook.getHouse().getId(),
                guestbook.getContent(),
                guestbook.getCreatedAt());
    }
}
