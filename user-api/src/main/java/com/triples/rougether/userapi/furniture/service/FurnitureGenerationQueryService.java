package com.triples.rougether.userapi.furniture.service;

import static com.triples.rougether.furniture.error.FurnitureGenerationErrorCode.FURNITURE_JOB_NOT_FOUND;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.furniture.repository.FurnitureGenerationJobRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.furniture.dto.FurnitureGenerationResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FurnitureGenerationQueryService {
    private final FurnitureGenerationJobRepository jobs;
    private final UserRepository users;

    public FurnitureGenerationResponse get(Long userId, String id) {
        requireActiveUser(userId);
        return FurnitureGenerationResponse.from(jobs.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(FURNITURE_JOB_NOT_FOUND)));
    }

    public List<FurnitureGenerationResponse> list(Long userId) {
        requireActiveUser(userId);
        return jobs.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).stream()
                .map(FurnitureGenerationResponse::from).toList();
    }

    private void requireActiveUser(Long userId) {
        users.findByIdAndDeletedAtIsNull(userId).orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
    }
}
