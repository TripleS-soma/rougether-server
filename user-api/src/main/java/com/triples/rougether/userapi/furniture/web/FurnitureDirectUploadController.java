package com.triples.rougether.userapi.furniture.web;

import com.triples.rougether.userapi.furniture.service.FurnitureDirectUploadService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "furniture.upload.enabled", havingValue = "true")
@RequestMapping("/api/v1/me/furniture-generations/uploads")
public class FurnitureDirectUploadController {
    private final FurnitureDirectUploadService service;
    public record Request(@NotNull UUID requestId, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String sha256,
                          @Min(1) @Max(10 * 1024 * 1024) long bytes,
                          @NotNull @Pattern(regexp = "image/(jpeg|png)") String contentType,
                          @Size(max = 120) String targetHint) { }

    @PostMapping
    public ResponseEntity<FurnitureDirectUploadService.Response> start(@CurrentUser AuthUser user, @Valid @RequestBody Request request) {
        var result = service.start(user.id(), request.requestId(), request.sha256(), request.bytes(), request.contentType(), request.targetHint());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/me/furniture-generations/" + result.job().id())).body(result);
    }
}
