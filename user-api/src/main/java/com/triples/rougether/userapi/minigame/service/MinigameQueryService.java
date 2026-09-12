package com.triples.rougether.userapi.minigame.service;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.minigame.entity.MinigameBestScore;
import com.triples.rougether.domain.minigame.repository.MinigameBestScoreRepository;
import com.triples.rougether.userapi.minigame.dto.MinigameLeaderboardResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameLeaderboardResponse.Entry;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MinigameQueryService {
    private static final int LEADERBOARD_SIZE = 50;
    private final UserRepository userRepository;
    private final MinigameBestScoreRepository bestScoreRepository;
    private final MinigameCatalog catalog;

    public MinigameLeaderboardResponse leaderboard(Long userId, String gameCode) {
        catalog.requireGame(gameCode);
        var user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .filter(found -> !found.isBot())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.USER_NOT_FOUND));
        List<MinigameBestScore> leaders = bestScoreRepository.findLeaderboard(gameCode,
                PageRequest.of(0, LEADERBOARD_SIZE));
        List<Entry> entries = new ArrayList<>(leaders.size());
        int previousScore = -1;
        long rank = 0;
        for (int i = 0; i < leaders.size(); i++) {
            MinigameBestScore best = leaders.get(i);
            if (previousScore != best.getScore()) {
                rank = i + 1L;
                previousScore = best.getScore();
            }
            entries.add(entry(rank, best));
        }
        Entry myEntry = bestScoreRepository.findByUserIdAndGameCode(userId, gameCode)
                .map(best -> new Entry(bestScoreRepository.countHigherScores(gameCode, best.getScore()) + 1,
                        userId, displayNickname(user.getNickname()), best.getScore()))
                .orElse(null);
        return new MinigameLeaderboardResponse(List.copyOf(entries), myEntry,
                bestScoreRepository.countPlayers(gameCode));
    }

    private static Entry entry(long rank, MinigameBestScore best) {
        return new Entry(rank, best.getUser().getId(), displayNickname(best.getUser().getNickname()), best.getScore());
    }

    private static String displayNickname(String nickname) {
        return nickname == null || nickname.isBlank() ? "이름 없는 이웃" : nickname;
    }
}
