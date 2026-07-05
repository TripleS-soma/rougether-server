package com.triples.rougether.adminapi.asset.web;

import com.triples.rougether.adminapi.asset.AssetKinds;
import com.triples.rougether.adminapi.asset.service.AssetStorageService;
import com.triples.rougether.adminapi.asset.service.AssetSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// 어드민 에셋 목록 조회. 조회 화면(/assets)이 kind 별로 호출해 썸네일·key 를 보여준다.
@RestController
@RequestMapping("/admin/assets")
public class AssetBrowseController {

    private final AssetStorageService storage;

    public AssetBrowseController(AssetStorageService storage) {
        this.storage = storage;
    }

    @GetMapping
    public AssetListResponse list(@RequestParam("kind") String kind) {
        if (!AssetKinds.ALLOWED.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 kind: " + kind);
        }
        return new AssetListResponse(storage.list(kind));
    }

    public record AssetListResponse(List<AssetSummary> items) {
    }
}
