package com.triples.rougether.domain.room.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// 새 서버로 트래픽 전환 후 실행하는 일회 소급 도구임. 앱 시작/Flyway에서 자동 실행하지 않음.
// 구버전 서버가 포인트 회수를 모르는 전환 구간에는 아직 과거 완료에 포인트를 적립하지 않아야 함.
public class PersonalRoomGrowthBackfill {

    private static final System.Logger log = System.getLogger(PersonalRoomGrowthBackfill.class.getName());

    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                requiredEnv("DB_URL"), requiredEnv("DB_USERNAME"), requiredEnv("DB_PASSWORD"))) {
            new PersonalRoomGrowthBackfill().run(connection);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 환경 변수가 필요함");
        }
        return value;
    }

    // 사용자별로 커밋함. 일부 사용자가 실패해도 완료 건의 지급 표식으로 중복 없이 재실행됨.
    public void run(Connection connection) throws Exception {
        if (!connection.getAutoCommit()) {
            throw new SQLException("성장 소급 반영은 별도 사용자별 트랜잭션 연결이 필요함");
        }
        List<Long> userIds = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT id FROM users WHERE deleted_at IS NULL AND is_bot = FALSE ORDER BY id");
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                userIds.add(rows.getLong(1));
            }
        }
        long totalPoints = 0;
        int changedUsers = 0;
        for (Long userId : userIds) {
            connection.setAutoCommit(false);
            try {
                long points = backfillUser(connection, userId);
                connection.commit();
                totalPoints = Math.addExact(totalPoints, points);
                if (points > 0) {
                    changedUsers++;
                }
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        log.log(System.Logger.Level.INFO, "개인 방 성장 소급 반영 완료: 대상 {0}명, 적립 {1}명, 추가 {2}포인트", userIds.size(), changedUsers, totalPoints);
    }

    private long backfillUser(Connection connection, long userId) throws SQLException {
        // 일반 SELECT보다 먼저 user → coin wallet 순서로 잠금함.
        // 구버전 완료도 coin wallet을 잠그므로 배포 중 완료/취소가 소급 합계와 엇갈리지 않음.
        try (var statement = connection.prepareStatement("""
                SELECT id FROM users WHERE id = ? AND deleted_at IS NULL AND is_bot = FALSE FOR UPDATE
                """)) {
            statement.setLong(1, userId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return 0;
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT id FROM user_wallets WHERE user_id = ? AND currency_type = 'COIN' FOR UPDATE")) {
            statement.setLong(1, userId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    // 잠금만 획득함. 잔액과 원장은 변경하지 않음.
                }
            }
        }
        List<Reward> routines = rewards(connection, """
                SELECT l.id, l.reward_amount - l.growth_reward_amount
                FROM routine_logs l
                WHERE l.routine_id IN (SELECT id FROM routines WHERE user_id = ?) AND l.status = 'COMPLETED' AND l.reward_currency_type = 'COIN'
                  AND l.growth_reward_amount >= 0 AND l.reward_amount > l.growth_reward_amount
                ORDER BY l.id FOR UPDATE
                """, userId);
        List<Reward> todos = rewards(connection, """
                SELECT id, reward_amount - growth_reward_amount FROM todos
                WHERE user_id = ? AND status = 'COMPLETED' AND reward_currency_type = 'COIN'
                  AND growth_reward_amount >= 0 AND reward_amount > growth_reward_amount
                ORDER BY id FOR UPDATE
                """, userId);
        long points = 0;
        for (Reward reward : routines) {
            points = Math.addExact(points, reward.points());
        }
        for (Reward reward : todos) {
            points = Math.addExact(points, reward.points());
        }
        if (points > 0) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO personal_rooms (user_id, growth_level, growth_points, highest_growth_level,
                                                layout_format, layout_revision, updated_at)
                    VALUES (?, 0, 0, 0, 'SLOT_V1', 0, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE user_id = personal_rooms.user_id
                    """)) {
                statement.setLong(1, userId);
                statement.executeUpdate();
            }
            addPoints(connection, userId, points);
            markRewards(connection, "UPDATE routine_logs SET growth_reward_amount = reward_amount WHERE id = ?", routines);
            markRewards(connection, "UPDATE todos SET growth_reward_amount = reward_amount WHERE id = ?", todos);
        }
        // 에셋 준비가 완료되어 이미 활성화된 모루만 지급함. 대표 선택과 회수된 보유 이력은 보존함.
        try (var statement = connection.prepareStatement("""
                INSERT INTO user_characters (user_id, character_id, is_selected, acquired_at, created_at, updated_at)
                SELECT r.user_id, c.id, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM personal_rooms r JOIN characters c ON c.code = 'moru' AND c.is_active = TRUE
                WHERE r.user_id = ? AND GREATEST(r.growth_level, r.highest_growth_level) >= 5
                  AND NOT EXISTS (SELECT 1 FROM user_characters uc WHERE uc.user_id = r.user_id AND uc.character_id = c.id)
                """)) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
        return points;
    }

    private List<Reward> rewards(Connection connection, String sql, long userId) throws SQLException {
        List<Reward> rewards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rewards.add(new Reward(rows.getLong(1), rows.getInt(2)));
                }
            }
        }
        return rewards;
    }

    private void addPoints(Connection connection, long userId, long delta) throws SQLException {
        long points;
        int previousLevel;
        int highestLevel;
        try (var statement = connection.prepareStatement("""
                SELECT growth_points, growth_level, highest_growth_level FROM personal_rooms WHERE user_id = ? FOR UPDATE
                """)) {
            statement.setLong(1, userId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("소급 대상 개인 방이 없음");
                }
                points = Math.addExact(rows.getLong(1), delta);
                previousLevel = rows.getInt(2);
                highestLevel = rows.getInt(3);
            }
        }
        int level = levelFor(points);
        try (var statement = connection.prepareStatement("""
                UPDATE personal_rooms SET growth_points = ?, growth_level = ?, highest_growth_level = ?,
                                          updated_at = CURRENT_TIMESTAMP WHERE user_id = ?
                """)) {
            statement.setLong(1, points);
            statement.setInt(2, level);
            statement.setInt(3, Math.max(highestLevel, Math.max(previousLevel, level)));
            statement.setLong(4, userId);
            statement.executeUpdate();
        }
    }

    private void markRewards(Connection connection, String sql, List<Reward> rewards) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (Reward reward : rewards) {
                statement.setLong(1, reward.id());
                statement.executeUpdate();
            }
        }
    }

    // 이번 소급의 확정 곡선을 고정함. 이후 밸런스 변경 시에는 별도 이행 도구를 사용해야 함.
    private int levelFor(long points) {
        long low = 0;
        long high = Math.min(points / 20, (long) Integer.MAX_VALUE + 1);
        while (low < high) {
            long middle = low + (high - low + 1) / 2;
            if (middle <= points / (middle + 19)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return Math.toIntExact(low);
    }

    private record Reward(long id, int points) {
    }
}
