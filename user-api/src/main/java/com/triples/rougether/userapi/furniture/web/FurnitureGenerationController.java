package com.triples.rougether.userapi.furniture.web;

import com.triples.rougether.userapi.furniture.dto.*;
import com.triples.rougether.userapi.furniture.service.FurnitureGenerationService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/me/furniture-generations")
@RequiredArgsConstructor
@Tag(name = "Furniture Generation", description = "사진으로 내 가구 만들기")
public class FurnitureGenerationController {
    private final FurnitureGenerationService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "가구 생성 작업 접수", description = "같은 requestId와 사진은 중복 생성하지 않음. JPEG/PNG, 10MB 이하.")
    public ResponseEntity<FurnitureGenerationResponse> create(@CurrentUser AuthUser user,
            @RequestParam UUID requestId, @RequestParam(defaultValue = "") @Size(max = 120) String targetHint,
            @RequestPart MultipartFile photo) {
        var result = service.submit(user.id(), requestId, targetHint, photo);
        return ResponseEntity.accepted().location(URI.create("/api/v1/me/furniture-generations/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    public record JobList(List<FurnitureGenerationResponse> items) { }
    @GetMapping
    @Operation(summary = "내 최근 가구 생성 작업 20개")
    public ResponseEntity<JobList> list(@CurrentUser AuthUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new JobList(service.list(user.id())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "가구 생성 상태 조회")
    public ResponseEntity<FurnitureGenerationResponse> get(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(user.id(), id.toString()));
    }

    @PostMapping("/{id}/feedback")
    @Operation(summary = "완성 가구 재검토", description = "Astra가 결과를 다시 보고 유지·부분 수정·재생성·거절을 판단함.")
    public ResponseEntity<FurnitureGenerationResponse> feedback(@CurrentUser AuthUser user, @PathVariable UUID id,
            @Valid @RequestBody FurnitureFeedbackRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service.feedback(user.id(), id.toString(), request));
    }
}
