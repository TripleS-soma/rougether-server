package com.triples.rougether.adminapi.notification.campaign.web;

import com.triples.rougether.adminapi.notification.campaign.dto.*;
import com.triples.rougether.adminapi.notification.campaign.error.NotificationCampaignException;
import com.triples.rougether.adminapi.notification.campaign.service.*;
import com.triples.rougether.common.error.ErrorResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/notification-campaigns")
@RequiredArgsConstructor
public class NotificationCampaignController {
    private final NotificationCampaignAudienceService audienceService;
    private final NotificationCampaignCommandService commandService;
    private final NotificationCampaignQueryService queryService;

    @GetMapping
    public List<NotificationCampaignResponse> list() { return queryService.list(); }

    @GetMapping("/{id}")
    public NotificationCampaignResponse get(@PathVariable Long id) { return queryService.get(id); }

    @PostMapping("/preview")
    public NotificationCampaignPreviewResponse preview(@Valid @RequestBody NotificationCampaignPreviewRequest request) {
        return audienceService.preview(request.audience(), request.userIds(), request.pushEnabled());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationCampaignResponse create(@Valid @RequestBody NotificationCampaignCreateRequest request,
                                                Principal principal) {
        return commandService.create(request, principal.getName());
    }

    @PostMapping("/{id}/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NotificationCampaignResponse send(@PathVariable Long id,
                                              @Valid @RequestBody NotificationCampaignSendRequest request,
                                              Principal principal) {
        return commandService.send(id, request, principal.getName());
    }

    @ExceptionHandler(NotificationCampaignException.class)
    public ResponseEntity<ErrorResponse> handle(NotificationCampaignException exception) {
        return ResponseEntity.status(exception.getStatus()).body(
                ErrorResponse.of(exception.getCode(), exception.getMessage()));
    }
}
