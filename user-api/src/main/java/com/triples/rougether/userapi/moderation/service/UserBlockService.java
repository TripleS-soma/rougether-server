package com.triples.rougether.userapi.moderation.service;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.UserBlock;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.moderation.dto.BlockedUserPageResponse;
import com.triples.rougether.userapi.moderation.dto.BlockedUserResponse;
import com.triples.rougether.userapi.moderation.error.ModerationErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 한 방향 사용자 차단(#399). 차단한 사람의 피드·댓글·알림·거래소 목록에서 상대를 뺌(각 조회 쿼리의 NOT EXISTS).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserBlockService {

    private final UserRepository users;
    private final UserBlockRepository blocks;
    private final Clock clock;

    // 이미 차단했으면 그대로 성공(멱등). 대상이 없거나 탈퇴·봇 계정이면 404.
    @Transactional
    public void block(Long userId, Long targetUserId) {
        requireNotSelf(userId, targetUserId);
        lockPair(userId, targetUserId);
        if (!blocks.existsByBlockerUserIdAndBlockedUserId(userId, targetUserId)) {
            blocks.save(new UserBlock(userId, targetUserId, clock.instant()));
        }
    }

    // 차단하지 않았거나 대상이 탈퇴했어도 성공(멱등).
    @Transactional
    public void unblock(Long userId, Long targetUserId) {
        requireNotSelf(userId, targetUserId);
        requireActive(users.findById(userId).orElse(null));
        blocks.deletePair(userId, targetUserId);
    }

    public BlockedUserPageResponse list(Long userId, Long cursor, int size) {
        requireActive(users.findById(userId).orElse(null));
        List<UserBlock> found = blocks.findPage(userId, cursor, PageRequest.of(0, size + 1));
        boolean more = found.size() > size;
        List<UserBlock> page = more ? found.subList(0, size) : found;
        Map<Long, User> blocked = users.findAllById(page.stream().map(UserBlock::getBlockedUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        // 탈퇴 시 차단 row 를 지우므로 탈퇴 회원은 정상 경로에선 없음. 경합으로 남은 row 는 표시만 건너뜀.
        List<BlockedUserResponse> items = page.stream()
                .map(b -> {
                    User user = blocked.get(b.getBlockedUserId());
                    return user == null || user.isDeleted() ? null
                            : new BlockedUserResponse(user.getId(), user.getNickname(), user.getProfileImageKey(),
                                    b.getCreatedAt());
                })
                .filter(Objects::nonNull)
                .toList();
        return new BlockedUserPageResponse(items, more ? page.getLast().getId() : null, more);
    }

    private static void requireNotSelf(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BusinessException(ModerationErrorCode.BLOCK_SELF);
        }
    }

    // 회원탈퇴의 회원 잠금과 직렬화해 탈퇴 정리 뒤에 차단 row 가 새로 생기지 않게 함.
    // 서로를 동시에 차단해도 잠금 순서가 교차하지 않도록 id 오름차순으로 잠금.
    private void lockPair(Long userId, Long targetUserId) {
        for (Long id : Stream.of(userId, targetUserId).sorted().toList()) {
            User locked = users.findByIdForUpdate(id).orElse(null);
            if (id.equals(userId)) {
                requireActive(locked);
            } else if (locked == null || locked.isDeleted() || locked.isBot()) {
                throw new BusinessException(MemberErrorCode.USER_NOT_FOUND);
            }
        }
    }

    private static void requireActive(User user) {
        if (user == null || user.isDeleted() || user.isBot()) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }
    }
}
