package com.triples.rougether.adminapi.catalog.web;

import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportResult;
import com.triples.rougether.adminapi.catalog.service.CatalogImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 어드민 카탈로그 일괄 적재. convert 가 만든 V1 JSON 을 올리면 멱등 적재한다.
@RestController
@RequestMapping("/admin/catalog")
public class CatalogImportController {

    private final CatalogImportService catalogImportService;

    public CatalogImportController(CatalogImportService catalogImportService) {
        this.catalogImportService = catalogImportService;
    }

    @PostMapping("/import")
    public CatalogImportResult importCatalog(@RequestBody CatalogImportRequest request) {
        return catalogImportService.importCatalog(request);
    }
}
