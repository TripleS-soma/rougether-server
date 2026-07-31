package com.triples.rougether.adminapi.accessoryrender.web;

import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileImportRequest;
import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileImportResult;
import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileListResponse;
import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileRow;
import com.triples.rougether.adminapi.accessoryrender.dto.AccessoryRenderProfileTransformUpdateRequest;
import com.triples.rougether.adminapi.accessoryrender.error.AccessoryRenderProfileInvalidException;
import com.triples.rougether.adminapi.accessoryrender.error.AccessoryRenderProfileNotFoundException;
import com.triples.rougether.adminapi.accessoryrender.service.AccessoryRenderProfileAdminService;
import com.triples.rougether.common.error.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/character-accessory-render-profiles")
public class AccessoryRenderProfileAdminController {

    private final AccessoryRenderProfileAdminService service;

    public AccessoryRenderProfileAdminController(
            AccessoryRenderProfileAdminService service) {
        this.service = service;
    }

    @GetMapping
    public AccessoryRenderProfileListResponse list() {
        return service.list();
    }

    @PostMapping("/import")
    public AccessoryRenderProfileImportResult importProfiles(
            @RequestBody List<AccessoryRenderProfileImportRequest> requests) {
        return service.importProfiles(requests);
    }

    @PutMapping("/{profileId}")
    public AccessoryRenderProfileRow updateTransform(
            @PathVariable Long profileId,
            @RequestBody AccessoryRenderProfileTransformUpdateRequest request) {
        return service.updateTransform(profileId, request);
    }

    @ExceptionHandler(AccessoryRenderProfileInvalidException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(
            AccessoryRenderProfileInvalidException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "CHARACTER_ACCESSORY_RENDER_PROFILE_INVALID",
                exception.getMessage()));
    }

    @ExceptionHandler(AccessoryRenderProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            AccessoryRenderProfileNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                "CHARACTER_ACCESSORY_RENDER_PROFILE_NOT_FOUND",
                exception.getMessage()));
    }
}
