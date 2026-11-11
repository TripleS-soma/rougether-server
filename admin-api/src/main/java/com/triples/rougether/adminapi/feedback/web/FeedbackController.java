package com.triples.rougether.adminapi.feedback.web;

import com.triples.rougether.adminapi.feedback.dto.*;
import com.triples.rougether.adminapi.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) { this.service = service; }

    @GetMapping("/updates")
    public FeedbackPage<AppUpdateResponse> updates(@RequestParam(required = false) @Positive Long cursor) {
        return service.updates(cursor);
    }

    @GetMapping("/suggestions")
    public FeedbackPage<SuggestionResponse> suggestions(
            @RequestParam(required = false) @Positive Long cursor) {
        return service.suggestions(cursor);
    }

    public record UpdateRequest(@NotBlank @Size(max = 100) String title,
                                @NotBlank @Size(max = 5000) String content) {}

    @PostMapping("/updates")
    @ResponseStatus(HttpStatus.CREATED)
    public AppUpdateResponse publish(@Valid @RequestBody UpdateRequest input) {
        return service.publish(input.title(), input.content());
    }

    @DeleteMapping("/updates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUpdate(@PathVariable @Positive Long id) { service.deleteUpdate(id); }

    @DeleteMapping("/suggestions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSuggestion(@PathVariable @Positive Long id) { service.deleteSuggestion(id); }
}
