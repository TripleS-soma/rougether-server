package com.triples.rougether.userapi.billing.web;

import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Store;
import com.triples.rougether.userapi.billing.service.FurnitureBillingService;
import com.triples.rougether.userapi.billing.store.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing/notifications")
@RequiredArgsConstructor
public class BillingNotificationController {
    private final ApplePurchaseVerifier apple;
    private final GoogleBillingNotificationVerifier google;
    private final FurnitureBillingService billing;
    public record AppleNotification(@NotBlank @Size(max = 65536) String signedPayload) { }
    public record GoogleMessage(@NotBlank @Size(max = 65536) String data) { }
    public record GoogleNotification(@NotNull @Valid GoogleMessage message) { }
    @PostMapping("/apple")
    public ResponseEntity<Void> apple(@Valid @RequestBody AppleNotification notification) {
        String reference = apple.notificationReference(notification.signedPayload());
        if (reference != null) billing.reconcile(Store.APPLE, reference);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/google")
    public ResponseEntity<Void> google(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody GoogleNotification notification) {
        String reference = google.reference(authorization, notification.message().data());
        if (reference != null) billing.reconcile(Store.GOOGLE, reference);
        return ResponseEntity.noContent().build();
    }
}
