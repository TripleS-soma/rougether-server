package com.triples.rougether.adminapi.user.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 유저 퍼널(이탈 지점) 응답. 단계는 누적 기준 - 이전 단계를 모두 거친 사용자만 세므로 단조 감소함.
// 비율은 화면 숫자뿐 아니라 분자·분모(가입자·직전 단계)와 이탈 인원을 함께 내려 산식을 재검증할 수 있게 함.
public record AdminUserFunnelResponse(
        LocalDate asOfDate,
        Instant generatedAt,
        int signupDays,
        SignupPeriod signupPeriod,
        List<StageMetric> stages,
        DropMetric biggestDrop,
        SideMetric sharedHouse,
        SideMetric withdrawn) {

    // 선언 순서가 곧 퍼널 순서. 가입(탈퇴·봇·AI 제외) → 온보딩(목표+대표 캐릭터) → 루틴·투두 생성(삭제돼도 유지)
    // → 첫 완료(루틴 로그 COMPLETED 또는 투두 COMPLETED) → 재방문(가입 다음 KST 일자 이후 마지막 인증 접속).
    public enum FunnelStage {
        SIGNED_UP,
        ONBOARDED,
        ROUTINE_CREATED,
        FIRST_COMPLETED,
        RETURNED
    }

    // signupDays=0(전체 기간)이면 응답의 signupPeriod 는 null.
    public record SignupPeriod(
            LocalDate fromDate,
            LocalDate toDate,
            int days) {

        public static SignupPeriod inclusive(LocalDate fromDate, LocalDate toDate) {
            int days = Math.toIntExact(toDate.toEpochDay() - fromDate.toEpochDay() + 1);
            return new SignupPeriod(fromDate, toDate, days);
        }
    }

    public record StageMetric(
            FunnelStage stage,
            long userCount,
            double signupPercentage,
            double previousStagePercentage,
            long droppedFromPrevious) {

        public static StageMetric of(FunnelStage stage, long userCount, long signupCount, long previousCount) {
            return new StageMetric(stage, userCount,
                    AdminUserFunnelResponse.percentage(userCount, signupCount),
                    AdminUserFunnelResponse.percentage(userCount, previousCount),
                    Math.max(previousCount - userCount, 0));
        }
    }

    // 직전 단계 대비 이탈 인원이 가장 큰 구간. 가입자가 없거나 이탈이 전혀 없으면 null.
    public record DropMetric(
            FunnelStage fromStage,
            FunnelStage toStage,
            long droppedUserCount,
            double dropPercentage) {

        public static DropMetric of(StageMetric from, StageMetric to) {
            return new DropMetric(from.stage(), to.stage(), to.droppedFromPrevious(),
                    AdminUserFunnelResponse.percentage(to.droppedFromPrevious(), from.userCount()));
        }
    }

    // 퍼널 순서와 무관한 부가 지표. 공동집 분모는 가입자, 탈퇴 분모는 가입자+탈퇴자임.
    public record SideMetric(
            long userCount,
            long denominatorCount,
            double percentage) {

        // 컴포넌트 percentage 가 같은 이름의 정적 산식을 가리므로 바깥 레코드로 한정해 호출함.
        public static SideMetric of(long userCount, long denominatorCount) {
            return new SideMetric(userCount, denominatorCount,
                    AdminUserFunnelResponse.percentage(userCount, denominatorCount));
        }
    }

    private static double percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }
}
