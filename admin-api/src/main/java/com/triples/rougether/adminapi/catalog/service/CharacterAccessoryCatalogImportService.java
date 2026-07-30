package com.triples.rougether.adminapi.catalog.service;

import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileImportRequest;
import com.triples.rougether.adminapi.accessoryrender.error.AccessoryRenderProfileInvalidException;
import com.triples.rougether.adminapi.accessoryrender.service.AccessoryRenderProfileAdminService;
import com.triples.rougether.adminapi.catalog.dto.CatalogImportRequest.ItemDto;
import com.triples.rougether.adminapi.catalog.dto.CharacterAccessoryCatalogImportRequest;
import com.triples.rougether.adminapi.catalog.dto.CharacterAccessoryCatalogImportResult;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterAccessoryCatalogImportService {

    private static final String DEFAULT_RENDER_STATE = "default";

    private final CatalogImportService catalogImportService;
    private final AccessoryRenderProfileAdminService renderProfileAdminService;

    public CharacterAccessoryCatalogImportService(
            CatalogImportService catalogImportService,
            AccessoryRenderProfileAdminService renderProfileAdminService) {
        this.catalogImportService = catalogImportService;
        this.renderProfileAdminService = renderProfileAdminService;
    }

    @Transactional
    public CharacterAccessoryCatalogImportResult importAll(
            CharacterAccessoryCatalogImportRequest request) {
        validateRequest(request);
        var catalogResult =
                catalogImportService.importCharacterAccessoryCatalog(request.catalog());
        var profileResult =
                renderProfileAdminService.importProfiles(request.renderProfiles());
        return new CharacterAccessoryCatalogImportResult(catalogResult, profileResult);
    }

    private void validateRequest(CharacterAccessoryCatalogImportRequest request) {
        if (request == null || request.catalog() == null || request.renderProfiles() == null) {
            throw invalid("카탈로그와 렌더 프로필 목록이 모두 필요합니다.");
        }
        List<ItemDto> items = request.catalog().items();
        if (items == null || items.isEmpty()) {
            throw invalid("캐릭터 악세사리 아이템 목록이 필요합니다.");
        }

        Set<String> defaultProfileItemKeys = request.renderProfiles().stream()
                .filter(profile -> profile != null
                        && DEFAULT_RENDER_STATE.equals(profile.renderState()))
                .map(AccessoryRenderProfileImportRequest::itemAssetKey)
                .collect(Collectors.toSet());
        List<String> missingDefaultProfiles = items.stream()
                .map(ItemDto::assetKey)
                .filter(assetKey -> !defaultProfileItemKeys.contains(assetKey))
                .toList();
        if (!missingDefaultProfiles.isEmpty()) {
            throw invalid("default 렌더 프로필이 없는 아이템이 있습니다: "
                    + String.join(", ", missingDefaultProfiles));
        }
    }

    private AccessoryRenderProfileInvalidException invalid(String message) {
        return new AccessoryRenderProfileInvalidException(message);
    }
}
