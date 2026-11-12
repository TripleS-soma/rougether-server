package com.triples.rougether.adminapi.user.web;

import com.triples.rougether.adminapi.user.dto.WithdrawalFeedbackSummary;
import com.triples.rougether.adminapi.user.service.WithdrawalFeedbackQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/users/withdrawal-feedback")
public class AdminWithdrawalFeedbackController {
    private final WithdrawalFeedbackQueryService service;

    @GetMapping
    public WithdrawalFeedbackSummary getSummary() {
        return service.getSummary();
    }
}
