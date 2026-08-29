package com.triples.rougether.batch.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// 탈퇴 purge 배치 - deleted_at 이 찍힌 유저의 잔여 데이터만 하드 삭제하고 purged_at 을 찍는다.
// FK 순서가 민감한 체인(사진→로그→루틴→카테고리, 배치·슬롯·악세사리→아이템, 방·캐릭터)을 전부 심어
// 삭제 순서 회귀를 잡는다. 전체 배치 컨텍스트 대신 트리거만 올린다 (기존 트리거 테스트 패턴 준용).
@SpringBootTest(classes = WithdrawalPurgeTriggerTest.TestConfig.class)
class WithdrawalPurgeTriggerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({WithdrawalPurgeTrigger.class, WithdrawalPurgeService.class})
    static class TestConfig {
    }

    @Autowired private WithdrawalPurgeTrigger trigger;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long withdrawnUserId;
    private Long activeUserId;
    private Long goalId;
    private Long themeId;
    private Long itemId;
    private Long attendanceEventId;
    private Long characterId;
    private Long houseId;

    @BeforeEach
    void setUp() {
        // access token 창(1시간) 소진 조건을 넘기기 위해 2시간 전 탈퇴로 둔다.
        withdrawnUserId = insertUser(null, Instant.now().minus(Duration.ofHours(2)));
        activeUserId = insertUser("잔류유저", null);
        insertMasters();
        insertUserData(withdrawnUserId, "withdrawn");
        insertUserData(activeUserId, "active");
        // 초대 보상 원장은 보존 대상 - 탈퇴자가 초대자인 지급 이력을 재현.
        jdbcTemplate.update("""
                INSERT INTO invite_rewards (inviter_user_id, invitee_user_id,
                                            inviter_reward_amount, invitee_reward_amount, created_at)
                VALUES (?, ?, 50, 50, ?)
                """, withdrawnUserId, activeUserId, now());
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM purged_asset_keys WHERE user_id IN (?, ?)",
                withdrawnUserId, activeUserId);
        jdbcTemplate.update("DELETE FROM invite_rewards WHERE inviter_user_id IN (?, ?)",
                withdrawnUserId, activeUserId);
        for (Long userId : new Long[] {withdrawnUserId, activeUserId}) {
            jdbcTemplate.update("""
                    DELETE pv FROM photo_verifications pv
                    JOIN routine_logs rl ON rl.id = pv.routine_log_id
                    JOIN routines r ON r.id = rl.routine_id WHERE r.user_id = ?
                    """, userId);
            jdbcTemplate.update(
                    "DELETE rl FROM routine_logs rl JOIN routines r ON r.id = rl.routine_id WHERE r.user_id = ?",
                    userId);
            jdbcTemplate.update("DELETE FROM routine_recommendations WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM routines WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM todos WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM categories WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM streaks WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM weekly_reports WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM room_item_placements WHERE room_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM room_surface_slots WHERE room_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM room_cobwebs WHERE room_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", userId);
            jdbcTemplate.update("""
                    DELETE uca FROM user_character_accessories uca
                    JOIN user_characters uc ON uc.id = uca.user_character_id WHERE uc.user_id = ?
                    """, userId);
            jdbcTemplate.update("DELETE FROM user_characters WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM attendance_check_ins WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_items WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);
            jdbcTemplate.update("""
                    DELETE dit FROM daily_incomplete_digest_targets dit
                    JOIN daily_incomplete_digests d ON d.id = dit.digest_id
                    WHERE d.user_id = ?
                    """, userId);
            jdbcTemplate.update("DELETE FROM daily_incomplete_digests WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM notification_setting WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_device_token WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_goals WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("""
                    DELETE bri FROM bug_report_images bri
                    JOIN bug_reports br ON br.id = bri.bug_report_id WHERE br.user_id = ?
                    """, userId);
            jdbcTemplate.update("DELETE FROM bug_reports WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM house_member_cheers WHERE sender_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM house_join_requests WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_invite_codes WHERE user_id = ?", userId);
        }
        jdbcTemplate.update("DELETE FROM house WHERE id = ?", houseId);
        jdbcTemplate.update("DELETE FROM user_goals WHERE goal_id = ?", goalId);
        jdbcTemplate.update("DELETE FROM goals WHERE id = ?", goalId);
        jdbcTemplate.update("DELETE FROM attendance_events WHERE id = ?", attendanceEventId);
        jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId);
        jdbcTemplate.update("DELETE FROM themes WHERE id = ?", themeId);
        jdbcTemplate.update("DELETE FROM characters WHERE id = ?", characterId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", withdrawnUserId, activeUserId);
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private Long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM " + table, Long.class);
    }

    private Long insertUser(String nickname, Instant deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO users (nickname, created_at, updated_at, deleted_at) VALUES (?, ?, ?, ?)",
                nickname, now(), now(), deletedAt == null ? null : Timestamp.from(deletedAt));
        return lastId("users");
    }

    // 마스터·공유 데이터(목표·테마·아이템·캐릭터·집)는 purge 대상이 아니므로 한 번만 심는다.
    private void insertMasters() {
        jdbcTemplate.update(
                "INSERT INTO goals (code, name, sort_order, is_active) VALUES (?, '목표', 0, false)",
                "purge-goal-" + System.nanoTime());
        goalId = lastId("goals");
        jdbcTemplate.update(
                "INSERT INTO themes (code, name, is_active) VALUES (?, '테마', false)",
                "purge-theme-" + System.nanoTime());
        themeId = lastId("themes");
        jdbcTemplate.update("""
                INSERT INTO items (theme_id, category_code, placement_type, surface_slot_type,
                                   character_slot_type, name, asset_key, is_limited, is_active)
                VALUES (?, 'FURNITURE', 'FLOOR', 'FLOOR', 'HAT', '아이템', 'item/purge.png', false, false)
                """, themeId);
        itemId = lastId("items");
        jdbcTemplate.update("""
                INSERT INTO attendance_events
                    (code, title, starts_on, ends_on, target_days, daily_coin_amount,
                     bonus_day, bonus_coin_amount, reward_item_id, is_active, created_at)
                VALUES (?, '파기 테스트 출석', ?, ?, 10, 30, 5, 50, ?, true, ?)
                """, "purge-attendance-" + System.nanoTime(), java.sql.Date.valueOf(LocalDate.now().minusDays(9)),
                java.sql.Date.valueOf(LocalDate.now().plusDays(20)), itemId, now());
        attendanceEventId = lastId("attendance_events");
        jdbcTemplate.update("""
                INSERT INTO characters (code, name, base_asset_key, sort_order, is_active)
                VALUES (?, '캐릭터', 'character/purge.png', 0, false)
                """, "purge-char-" + System.nanoTime());
        characterId = lastId("characters");
        jdbcTemplate.update("""
                INSERT INTO house (owner_user_id, name, current_member_count, level, growth_points,
                                   created_at, updated_at)
                VALUES (?, 'purge 테스트 하우스', 1, 0, 0, ?, ?)
                """, activeUserId, now(), now());
        houseId = lastId("house");
    }

    // users 를 참조하는 개인 데이터 전 체인을 심는다(FK 순서 회귀 검증용).
    private void insertUserData(Long userId, String tag) {
        jdbcTemplate.update("""
                INSERT INTO categories (user_id, name, sort_order, created_at, updated_at)
                VALUES (?, '카테고리', 0, ?, ?)
                """, userId, now(), now());
        Long categoryId = lastId("categories");
        jdbcTemplate.update("""
                INSERT INTO routines (user_id, category_id, title, auth_type, status, created_at, updated_at)
                VALUES (?, ?, '루틴', 'PHOTO', 'ACTIVE', ?, ?)
                """, userId, categoryId, now(), now());
        Long routineId = lastId("routines");
        jdbcTemplate.update("""
                INSERT INTO routine_logs (routine_id, routine_date, status, reward_amount, created_at)
                VALUES (?, ?, 'COMPLETED', 0, ?)
                """, routineId, java.sql.Date.valueOf(LocalDate.now()), now());
        Long logId = lastId("routine_logs");
        jdbcTemplate.update("""
                INSERT INTO photo_verifications (routine_log_id, storage_key, uploaded_at)
                VALUES (?, ?, ?)
                """, logId, "verification/" + tag + ".jpg", now());
        jdbcTemplate.update("""
                INSERT INTO todos (user_id, category_id, title, reward_amount, created_at, updated_at)
                VALUES (?, ?, '투두', 0, ?, ?)
                """, userId, categoryId, now(), now());
        jdbcTemplate.update("""
                INSERT INTO streaks (user_id, current_count, longest_count, status, updated_at)
                VALUES (?, 1, 1, 'ACTIVE', ?)
                """, userId, now());
        jdbcTemplate.update("""
                INSERT INTO weekly_reports (user_id, week_start_date, week_end_date, status, stats_json, summary,
                                            sections_json, generated_at, created_at)
                VALUES (?, '2026-08-09', '2026-08-15', 'FALLBACK', '{}', '회고', '{}', ?, ?)
                """, userId, now(), now());
        // 조정 추천(#329) — routines 를 3중 FK(계보·대상·적용 버전)로 참조하므로 purge 순서 회귀의 핵심 fixture
        jdbcTemplate.update("""
                INSERT INTO routine_recommendations (user_id, origin_routine_id, routine_id, rec_type, source,
                                                     proposal, message, status, expires_at, created_at)
                VALUES (?, ?, ?, 'ADJUST_DAYS', 'RULE', '{"repeatType":"WEEKLY","daysOfWeek":["MON"]}',
                        '추천', 'ACTIVE', ?, ?)
                """, userId, routineId, routineId, now(), now());

        jdbcTemplate.update("INSERT INTO user_items (user_id, item_id, acquired_at) VALUES (?, ?, ?)",
                userId, itemId, now());
        Long userItemId = lastId("user_items");
        jdbcTemplate.update("""
                INSERT INTO attendance_check_ins
                    (event_id, user_id, attendance_date, streak_day, coin_reward_amount,
                     reward_user_item_id, reward_newly_granted, reward_processed_at, created_at)
                VALUES (?, ?, ?, 10, 30, ?, true, ?, ?)
                """, attendanceEventId, userId, java.sql.Date.valueOf(LocalDate.now()),
                userItemId, now(), now());
        jdbcTemplate.update("INSERT INTO personal_rooms (user_id, growth_level, updated_at) VALUES (?, 0, ?)",
                userId, now());
        jdbcTemplate.update("""
                INSERT INTO room_cobwebs (room_user_id, appeared_at, cleaned_at, cleaned_by_user_id, updated_at)
                VALUES (?, ?, NULL, NULL, ?)
                """, userId, now(), now());
        jdbcTemplate.update("""
                INSERT INTO room_item_placements (room_user_id, user_item_id, position_x, position_y,
                                                  z_index, scale, rotation_deg, flipped, updated_at)
                VALUES (?, ?, 0.5, 0.5, 0, 1.0, 0, false, ?)
                """, userId, userItemId, now());
        jdbcTemplate.update("""
                INSERT INTO room_surface_slots (room_user_id, slot_type, user_item_id, saved_at)
                VALUES (?, 'FLOOR', ?, ?)
                """, userId, userItemId, now());
        jdbcTemplate.update("""
                INSERT INTO user_characters (user_id, character_id, is_selected, acquired_at,
                                             created_at, updated_at)
                VALUES (?, ?, true, ?, ?, ?)
                """, userId, characterId, now(), now(), now());
        Long userCharacterId = lastId("user_characters");
        jdbcTemplate.update("""
                INSERT INTO user_character_accessories (user_character_id, user_item_id,
                                                        character_slot_type, equipped_at)
                VALUES (?, ?, 'HAT', ?)
                """, userCharacterId, userItemId, now());
        jdbcTemplate.update("""
                INSERT INTO user_wallets (user_id, currency_type, balance, created_at, updated_at)
                VALUES (?, 'COIN', 0, ?, ?)
                """, userId, now(), now());

        jdbcTemplate.update("""
                INSERT INTO notification (user_id, type, title, body, created_at)
                VALUES (?, 'ROUTINE_REMIND', '제목', '내용', ?)
                """, userId, now());
        Long notificationId = lastId("notification");
        jdbcTemplate.update("""
                INSERT INTO daily_incomplete_digests
                    (user_id, digest_date, routine_count, todo_count, notification_id,
                     push_status, created_at, updated_at)
                VALUES (?, ?, 1, 0, ?, 'SENT', ?, ?)
                """, userId, java.sql.Date.valueOf(LocalDate.now()), notificationId, now(), now());
        Long digestId = lastId("daily_incomplete_digests");
        jdbcTemplate.update("""
                INSERT INTO daily_incomplete_digest_targets
                    (digest_id, target_type, target_id, created_at)
                VALUES (?, 'ROUTINE', ?, ?)
                """, digestId, routineId, now());
        jdbcTemplate.update("""
                INSERT INTO notification_setting (user_id, type, enabled, created_at, updated_at)
                VALUES (?, 'ALL', true, ?, ?)
                """, userId, now(), now());
        jdbcTemplate.update("""
                INSERT INTO user_device_token (user_id, token, platform, created_at, updated_at)
                VALUES (?, ?, 'ANDROID', ?, ?)
                """, userId, "fcm-" + tag, now(), now());
        jdbcTemplate.update(
                "INSERT INTO user_goals (user_id, goal_id, is_primary, created_at) VALUES (?, ?, true, ?)",
                userId, goalId, now());
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?)
                """, userId, "hash-" + tag, now(), now());

        jdbcTemplate.update("""
                INSERT INTO bug_reports (user_id, title, content, status, created_at, updated_at)
                VALUES (?, '버그', '내용', 'RECEIVED', ?, ?)
                """, userId, now(), now());
        Long reportId = lastId("bug_reports");
        jdbcTemplate.update(
                "INSERT INTO bug_report_images (bug_report_id, storage_key, sort_order) VALUES (?, ?, 0)",
                reportId, "bugreport/" + tag + ".jpg");

        jdbcTemplate.update("""
                INSERT INTO house_member_cheers (house_id, sender_user_id, target_user_id,
                                                 cheer_type, cheer_date, daily_seq, created_at)
                VALUES (?, ?, ?, 'FIGHTING', ?, 1, ?)
                """, houseId, userId, userId, java.sql.Date.valueOf(LocalDate.now()), now());
        jdbcTemplate.update("""
                INSERT INTO house_join_requests (house_id, user_id, status, requested_at)
                VALUES (?, ?, 'REJECTED', ?)
                """, houseId, userId, now());
        jdbcTemplate.update(
                "INSERT INTO user_invite_codes (user_id, code, created_at) VALUES (?, ?, ?)",
                userId, "CODE" + userId, now());
    }

    private int countFor(String table, String userColumn, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + userColumn + " = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private int accessoryCountFor(Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_character_accessories uca
                JOIN user_characters uc ON uc.id = uca.user_character_id WHERE uc.user_id = ?
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    private Timestamp purgedAtOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT purged_at FROM users WHERE id = ?", Timestamp.class, userId);
    }

    @Test
    void 탈퇴_유저의_잔여_데이터만_하드_삭제하고_purged_at_을_찍는다() {
        trigger.purgeWithdrawnUsers();

        for (String[] tableAndColumn : new String[][] {
                {"categories", "user_id"}, {"routines", "user_id"}, {"todos", "user_id"},
                {"streaks", "user_id"}, {"weekly_reports", "user_id"}, {"routine_recommendations", "user_id"},
                {"personal_rooms", "user_id"},
                {"room_item_placements", "room_user_id"}, {"room_surface_slots", "room_user_id"},
                {"room_cobwebs", "room_user_id"},
                {"user_characters", "user_id"}, {"attendance_check_ins", "user_id"},
                {"user_items", "user_id"}, {"user_wallets", "user_id"},
                {"daily_incomplete_digests", "user_id"},
                {"notification", "user_id"}, {"notification_setting", "user_id"},
                {"user_device_token", "user_id"}, {"user_goals", "user_id"},
                {"refresh_tokens", "user_id"}, {"bug_reports", "user_id"},
                {"house_member_cheers", "sender_user_id"}, {"house_join_requests", "user_id"},
                {"user_invite_codes", "user_id"}}) {
            assertThat(countFor(tableAndColumn[0], tableAndColumn[1], withdrawnUserId))
                    .as("%s 잔존", tableAndColumn[0]).isZero();
            assertThat(countFor(tableAndColumn[0], tableAndColumn[1], activeUserId))
                    .as("%s 잔류 유저 데이터 소실", tableAndColumn[0]).isEqualTo(1);
        }
        assertThat(accessoryCountFor(withdrawnUserId)).isZero();
        assertThat(accessoryCountFor(activeUserId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM daily_incomplete_digest_targets dit
                JOIN daily_incomplete_digests d ON d.id = dit.digest_id
                WHERE d.user_id = ?
                """, Integer.class, activeUserId)).isEqualTo(1);
        // 초대 보상 원장은 초대자 지급 한도 카운트 보존을 위해 남긴다
        assertThat(countFor("invite_rewards", "inviter_user_id", withdrawnUserId)).isEqualTo(1);
        // users row 는 FK 앵커(방명록 author 등)로 남고 purged_at 만 찍힌다
        assertThat(purgedAtOf(withdrawnUserId)).isNotNull();
        assertThat(purgedAtOf(activeUserId)).isNull();
        // 이미지 row 삭제 전에 S3 key 가 파기 대기열로 옮겨진다(후속 원본 삭제 경로 보존)
        assertThat(jdbcTemplate.queryForList(
                "SELECT storage_key FROM purged_asset_keys WHERE user_id = ?", String.class, withdrawnUserId))
                .containsExactlyInAnyOrder("verification/withdrawn.jpg", "bugreport/withdrawn.jpg");
        assertThat(countFor("purged_asset_keys", "user_id", activeUserId)).isZero();
    }

    @Test
    void 탈퇴_후_1시간이_지나지_않은_유저는_이번_실행에서_제외된다() {
        Long recentlyWithdrawnId = insertUser(null, Instant.now().minus(Duration.ofMinutes(10)));
        try {
            trigger.purgeWithdrawnUsers();

            assertThat(purgedAtOf(recentlyWithdrawnId)).isNull();
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", recentlyWithdrawnId);
        }
    }

    @Test
    void 재실행해도_이미_purge_된_유저의_표식은_그대로고_예외가_없다() {
        trigger.purgeWithdrawnUsers();
        Timestamp firstPurgedAt = purgedAtOf(withdrawnUserId);

        trigger.purgeWithdrawnUsers();

        // 재스캔 대상에서 빠져 purged_at 이 갱신되지 않는다(멱등).
        assertThat(purgedAtOf(withdrawnUserId)).isEqualTo(firstPurgedAt);
    }
}
