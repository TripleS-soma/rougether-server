package com.triples.rougether.userapi.billing.web;

import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Store;
import com.triples.rougether.userapi.billing.service.*;
import com.triples.rougether.userapi.global.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/furniture-credits")
@RequiredArgsConstructor
public class FurnitureBillingController {
    private final FurnitureBillingService billing;
    private final FurnitureCreditTransactions credits;
    public record PurchaseRequest(@NotNull Store store, @NotBlank @Size(max = 4096) String reference) { }
    @GetMapping
    public ResponseEntity<FurnitureCreditTransactions.Balance> balance(@CurrentUser AuthUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(credits.balance(user.id()));
    }
    @GetMapping("/products")
    public ResponseEntity<FurnitureBillingService.Products> products(@RequestParam Store store) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(billing.products(store));
    }
    @PostMapping("/purchases")
    public ResponseEntity<FurnitureCreditTransactions.Receipt> purchase(@CurrentUser AuthUser user,
            @Valid @RequestBody PurchaseRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(billing.confirm(user.id(), request.store(), request.reference()));
    }
}
