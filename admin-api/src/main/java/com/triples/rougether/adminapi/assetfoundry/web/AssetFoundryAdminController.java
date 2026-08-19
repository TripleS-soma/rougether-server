package com.triples.rougether.adminapi.assetfoundry.web;

import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineCreateRequest;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineJobResponse;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineListResponse;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineQaUpdateRequest;
import com.triples.rougether.adminapi.assetfoundry.dto.AssetPipelineStatusUpdateRequest;
import com.triples.rougether.adminapi.assetfoundry.error.AssetFoundryException;
import com.triples.rougether.adminapi.assetfoundry.service.AssetFoundryService;
import com.triples.rougether.common.error.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/asset-foundry")
public class AssetFoundryAdminController {

    private final AssetFoundryService assetFoundryService;

    public AssetFoundryAdminController(AssetFoundryService assetFoundryService) {
        this.assetFoundryService = assetFoundryService;
    }

    @GetMapping("/jobs")
    public AssetPipelineListResponse getJobs() {
        return assetFoundryService.list();
    }

    @PostMapping("/jobs")
    public ResponseEntity<AssetPipelineJobResponse> createJob(
            @RequestBody AssetPipelineCreateRequest request,
            Authentication authentication) {
        return ResponseEntity.status(201)
                .body(assetFoundryService.create(request, authentication.getName()));
    }

    @PutMapping("/jobs/{jobId}/status")
    public AssetPipelineJobResponse updateStatus(
            @PathVariable Long jobId,
            @RequestBody AssetPipelineStatusUpdateRequest request,
            Authentication authentication) {
        return assetFoundryService.updateStatus(jobId, request, authentication.getName());
    }

    @PutMapping("/jobs/{jobId}/qa")
    public AssetPipelineJobResponse updateQa(
            @PathVariable Long jobId,
            @RequestBody AssetPipelineQaUpdateRequest request,
            Authentication authentication) {
        return assetFoundryService.updateQa(jobId, request, authentication.getName());
    }

    @ExceptionHandler(AssetFoundryException.class)
    public ResponseEntity<ErrorResponse> handleAssetFoundry(AssetFoundryException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ErrorResponse.of(exception.getCode(), exception.getMessage()));
    }
}
