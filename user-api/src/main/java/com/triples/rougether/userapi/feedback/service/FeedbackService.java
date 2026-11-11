package com.triples.rougether.userapi.feedback.service;

import com.triples.rougether.domain.feedback.entity.AppUpdate;
import com.triples.rougether.domain.feedback.entity.Suggestion;
import com.triples.rougether.domain.feedback.repository.AppUpdateRepository;
import com.triples.rougether.domain.feedback.repository.SuggestionRepository;
import com.triples.rougether.userapi.feedback.dto.*;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FeedbackService {
    private static final int PAGE_SIZE = 20;
    private final SuggestionRepository suggestions;
    private final AppUpdateRepository updates;
    private final UserRepository users;

    public FeedbackService(SuggestionRepository suggestions, AppUpdateRepository updates, UserRepository users) {
        this.suggestions = suggestions;
        this.updates = updates;
        this.users = users;
    }

    public FeedbackPage<AppUpdateResponse> updates(Long cursor) {
        List<AppUpdate> found = updates.findByIdLessThanOrderByIdDesc(
                cursor == null ? Long.MAX_VALUE : cursor, PageRequest.of(0, PAGE_SIZE + 1));
        List<AppUpdate> page = found.stream().limit(PAGE_SIZE).toList();
        return new FeedbackPage<>(page.stream().map(AppUpdateResponse::of).toList(),
                found.size() > PAGE_SIZE ? page.getLast().getId() : null);
    }

    public FeedbackPage<SuggestionResponse> suggestions(Long userId, Long cursor) {
        List<Suggestion> found = suggestions.findByUserIdAndIdLessThanOrderByIdDesc(userId,
                cursor == null ? Long.MAX_VALUE : cursor, PageRequest.of(0, PAGE_SIZE + 1));
        List<Suggestion> page = found.stream().limit(PAGE_SIZE).toList();
        return new FeedbackPage<>(page.stream().map(SuggestionResponse::of).toList(),
                found.size() > PAGE_SIZE ? page.getLast().getId() : null);
    }

    @Transactional
    public SuggestionResponse submit(Long userId, String title, String content) {
        var user = users.findByIdForUpdate(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
        return SuggestionResponse.of(suggestions.save(Suggestion.create(user, title, content)));
    }
}
