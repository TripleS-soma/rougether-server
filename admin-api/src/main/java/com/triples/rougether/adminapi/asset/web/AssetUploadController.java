package com.triples.rougether.adminapi.asset.web;

import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

// 어드민 범용 이미지 업로드. multipart 이미지 → S3 → object key 반환.
// 발급된 key 는 각 도메인(items.asset_key, characters.base_asset_key 등)이 저장한다.
@RestController
@RequestMapping("/admin/assets")
public class AssetUploadController {

    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> ALLOWED_KINDS =
            Set.of("characters", "categories", "themes", "items", "house");
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    private final AssetStorageService storage;

    public AssetUploadController(AssetStorageService storage) {
        this.storage = storage;
    }

    @PostMapping
    public AssetUploadResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("kind") String kind) throws IOException {
        validate(file, kind);
        String key = storage.upload(file.getBytes(), file.getContentType(), kind);
        return new AssetUploadResponse(key, file.getContentType(), file.getSize());
    }

    private void validate(MultipartFile file, String kind) {
        if (file.isEmpty()) {
            throw badRequest("파일이 비어 있습니다.");
        }
        if (!ALLOWED_KINDS.contains(kind)) {
            throw badRequest("허용되지 않은 kind: " + kind);
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw badRequest("허용되지 않은 이미지 형식: " + file.getContentType());
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw badRequest("이미지 크기는 10MB 이하만 허용됩니다.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
