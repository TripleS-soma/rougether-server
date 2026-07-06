package com.triples.rougether.userapi.guestbook.web;

import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateRequest;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateResponse;
import com.triples.rougether.userapi.guestbook.dto.GuestbookListResponse;
import com.triples.rougether.userapi.guestbook.service.GuestbookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 방명록 - 방 주인과 같은 집 구성원만 조회/작성. 커서 기반 무한스크롤.
@Tag(name = "Guestbook", description = "방명록 관련 API")
@RestController
@RequestMapping("/api/v1/rooms/{roomOwnerId}/guestbooks")
public class GuestbookController {

    private final GuestbookService guestbookService;

    public GuestbookController(GuestbookService guestbookService) {
        this.guestbookService = guestbookService;
    }

    @Operation(summary = "방명록 목록 조회",
            description = "방 주인의 방명록을 최신순으로 조회합니다. 방 주인과 같은 집(houseId)의 ACTIVE 구성원만 조회할 수 있습니다. "
                    + "무한스크롤용 커서 방식입니다 — 첫 요청은 cursor 없이 보내고, 다음 페이지는 응답의 nextCursor 를 "
                    + "cursor 로 전달합니다(hasNext=false 면 끝). 스크롤 도중 새 글이 등록돼도 중복·누락 없이 이어집니다.")
    @GetMapping
    public GuestbookListResponse getGuestbooks(
            @CurrentUser AuthUser user,
            @Parameter(description = "방 주인 회원 ID. GET /api/v1/houses/{houseId}/members (구성원 목록) 응답의 userId 값")
            @PathVariable Long roomOwnerId,
            @Parameter(description = "집 ID (방 주인과 함께 속한 집). GET /api/v1/me/houses (내 집 목록) 응답의 houseId 값")
            @RequestParam Long houseId,
            @Parameter(description = "커서 (이전 응답의 nextCursor. 첫 요청은 생략)")
            @RequestParam(required = false) Long cursor,
            @Parameter(description = "페이지 크기 (기본 20, 최대 50)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return guestbookService.getGuestbooks(user.id(), roomOwnerId, houseId, cursor, size);
    }

    @Operation(summary = "방명록 작성",
            description = "방 주인의 방에 방명록을 남깁니다. 방 주인과 같은 집(houseId)의 ACTIVE 구성원만 작성할 수 있고 "
                    + "방 주인 본인도 자기 방에 쓸 수 있습니다. 내용은 1~500자입니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public GuestbookCreateResponse write(
            @CurrentUser AuthUser user,
            @Parameter(description = "방 주인 회원 ID. GET /api/v1/houses/{houseId}/members (구성원 목록) 응답의 userId 값")
            @PathVariable Long roomOwnerId,
            @Valid @RequestBody GuestbookCreateRequest request) {
        return guestbookService.write(user.id(), roomOwnerId, request);
    }
}
