package com.triples.rougether.userapi.feedback.web;

import com.triples.rougether.userapi.feedback.dto.*;
import com.triples.rougether.userapi.feedback.service.FeedbackService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) { this.service = service; }

    @GetMapping("/updates")
    public FeedbackPage<AppUpdateResponse> updates(@RequestParam(required = false) @Positive Long cursor) {
        return service.updates(cursor);
    }

    @GetMapping("/me/suggestions")
    public FeedbackPage<SuggestionResponse> suggestions(@CurrentUser AuthUser user,
            @RequestParam(required = false) @Positive Long cursor) {
        return service.suggestions(user.id(), cursor);
    }

    public record SuggestionRequest(@NotBlank @Size(max = 100) String title,
                                   @NotBlank @Size(max = 2000) String content) {}

    @PostMapping("/suggestions")
    @ResponseStatus(HttpStatus.CREATED)
    public SuggestionResponse submit(@CurrentUser AuthUser user,
                                     @Valid @RequestBody SuggestionRequest input) {
        return service.submit(user.id(), input.title(), input.content());
    }
}
