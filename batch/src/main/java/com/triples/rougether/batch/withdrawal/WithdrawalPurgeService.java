package com.triples.rougether.batch.withdrawal;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 탈퇴 유저 1명의 잔여 데이터 하드 삭제(유저 단위 단일 트랜잭션). FK 자식 → 부모 순서 준수.
// 남기는 것: users(익명 skeleton — 방명록 author 등 FK 앵커), oauth_accounts(탈퇴 시 이미 삭제),
// house·house_members·house_mission_*(집 이력·미션 기여는 남은 구성원 통계 소유), room_guestbooks(탈퇴한 사용자 표시),
// invite_rewards(초대 보상 원장) — 살아있는 초대자의 지급 한도(10회) 카운트가 invitee 탈퇴로 리셋되지 않게 보존함.
// 단, 탈퇴→재가입은 새 users.id 를 받아 invitee 평생 1회 판정은 이 보존과 무관하게 뚫림(기존 InviteService 설계 한계, 별도 트래킹).
// S3 원본(인증사진·버그리포트 이미지)은 아직 삭제하지 않음 — row 만 지워 접근 경로를 끊고, 원본 파기는 후속(IAM delete 권한 정책 확정 후).
// 이미지 row 가 object key 의 유일한 매핑이라, row 삭제 전에 key 를 purged_asset_keys 로 옮겨 후속 파기 경로를 보존함.
@Service
@RequiredArgsConstructor
public class WithdrawalPurgeService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void purgeUser(Long userId, Instant now) {
        // 이미지 S3 key 보존 — 아래에서 row 가 지워지기 전에 같은 트랜잭션으로 대기열에 적재.
        Timestamp nowTs = Timestamp.from(now);
        jdbcTemplate.update("""
                INSERT INTO purged_asset_keys (user_id, storage_key, created_at)
                SELECT r.user_id, pv.storage_key, ? FROM photo_verifications pv
                JOIN routine_logs rl ON rl.id = pv.routine_log_id
                JOIN routines r ON r.id = rl.routine_id
                WHERE r.user_id = ?
                """, nowTs, userId);
        jdbcTemplate.update("""
                INSERT INTO purged_asset_keys (user_id, storage_key, created_at)
                SELECT br.user_id, bri.storage_key, ? FROM bug_report_images bri
                JOIN bug_reports br ON br.id = bri.bug_report_id
                WHERE br.user_id = ?
                """, nowTs, userId);

        // 루틴 계열: 인증사진 → 로그 → 스트릭 → 투두 → 루틴 → 카테고리
        jdbcTemplate.update("""
                DELETE pv FROM photo_verifications pv
                JOIN routine_logs rl ON rl.id = pv.routine_log_id
                JOIN routines r ON r.id = rl.routine_id
                WHERE r.user_id = ?
                """, userId);
        jdbcTemplate.update(
                "DELETE rl FROM routine_logs rl JOIN routines r ON r.id = rl.routine_id WHERE r.user_id = ?",
                userId);
        jdbcTemplate.update("DELETE FROM streaks WHERE user_id = ?", userId);
        // 주간 회고(#286)는 루틴 로그·스트릭·목표·닉네임/bio 로 만든 파생 데이터라 원본과 함께 지운다
        jdbcTemplate.update("DELETE FROM weekly_reports WHERE user_id = ?", userId);
        // 조정 추천(#329)도 루틴 로그 파생 데이터이며 routines FK(계보·대상·적용 버전) 자식이라 루틴보다 먼저 지운다
        jdbcTemplate.update("DELETE FROM routine_recommendations WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM todos WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM routines WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM categories WHERE user_id = ?", userId);

        // 방 계열: 배치·표면슬롯·거미줄 → 개인 방 (user_items 참조가 풀린 뒤에 아이템 삭제 가능)
        jdbcTemplate.update("DELETE FROM room_item_placements WHERE room_user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM room_surface_slots WHERE room_user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM room_cobwebs WHERE room_user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM personal_rooms WHERE user_id = ?", userId);

        // 캐릭터·이벤트·아이템·지갑: 아이템을 참조하는 악세사리·출석 이력을 먼저 삭제함.
        jdbcTemplate.update("""
                DELETE uca FROM user_character_accessories uca
                JOIN user_characters uc ON uc.id = uca.user_character_id
                WHERE uc.user_id = ?
                """, userId);
        jdbcTemplate.update("DELETE FROM user_characters WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM attendance_check_ins WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_items WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_wallets WHERE user_id = ?", userId);

        // 관측·알림·목표·토큰: 탈퇴 트랜잭션에서 일부를 이미 지웠어도 과거 탈퇴분 보정 겸 멱등 재실행
        jdbcTemplate.update("DELETE FROM user_daily_activity WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM notification_setting WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_device_token WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_goals WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);

        // 버그리포트: 이미지 → 본문
        jdbcTemplate.update("""
                DELETE bri FROM bug_report_images bri
                JOIN bug_reports br ON br.id = bri.bug_report_id
                WHERE br.user_id = ?
                """, userId);
        jdbcTemplate.update("DELETE FROM bug_reports WHERE user_id = ?", userId);

        // 집 상호작용·초대: 응원(양방향)·입주신청·초대코드. invite_rewards 는 보상 불변식 원장이라 보존(클래스 주석).
        jdbcTemplate.update(
                "DELETE FROM house_member_cheers WHERE sender_user_id = ? OR target_user_id = ?",
                userId, userId);
        jdbcTemplate.update("DELETE FROM house_join_requests WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_invite_codes WHERE user_id = ?", userId);

        jdbcTemplate.update("UPDATE users SET purged_at = ? WHERE id = ?", nowTs, userId);
    }
}
