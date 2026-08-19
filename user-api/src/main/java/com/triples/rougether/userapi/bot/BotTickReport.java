package com.triples.rougether.userapi.bot;

// 틱 1회 처리 결과 집계(#310) — 진입/종료 로그와 테스트 단언용. 행동 1건 = 트랜잭션 1개이므로 실패는 건 단위로 센다.
public final class BotTickReport {

    private int botsSeen;
    private int botsActed;
    private int routinesCompleted;
    private int missionsContributed;
    private int cheersSent;
    private int guestbooksWritten;
    private int cobwebsCleaned;
    private int layoutsRotated;
    private int businessSkips;
    private int failures;

    void botSeen() {
        botsSeen++;
    }

    void botActed() {
        botsActed++;
    }

    void routineCompleted() {
        routinesCompleted++;
    }

    void missionContributed() {
        missionsContributed++;
    }

    void cheerSent() {
        cheersSent++;
    }

    void guestbookWritten() {
        guestbooksWritten++;
    }

    void cobwebCleaned() {
        cobwebsCleaned++;
    }

    void layoutRotated() {
        layoutsRotated++;
    }

    // 기존 서비스가 비즈니스 규칙으로 거절한 건(이미 완료·거미줄 없음·revision 충돌 등 정상 경합 흡수) — 장애가 아니다.
    void businessSkipped() {
        businessSkips++;
    }

    void failed() {
        failures++;
    }

    public int botsSeen() {
        return botsSeen;
    }

    public int botsActed() {
        return botsActed;
    }

    public int routinesCompleted() {
        return routinesCompleted;
    }

    public int missionsContributed() {
        return missionsContributed;
    }

    public int cheersSent() {
        return cheersSent;
    }

    public int guestbooksWritten() {
        return guestbooksWritten;
    }

    public int cobwebsCleaned() {
        return cobwebsCleaned;
    }

    public int layoutsRotated() {
        return layoutsRotated;
    }

    public int businessSkips() {
        return businessSkips;
    }

    public int failures() {
        return failures;
    }

    public int totalActions() {
        return routinesCompleted + missionsContributed + cheersSent + guestbooksWritten + cobwebsCleaned + layoutsRotated;
    }

    @Override
    public String toString() {
        return "bots=" + botsActed + "/" + botsSeen
                + ", routines=" + routinesCompleted
                + ", missions=" + missionsContributed
                + ", cheers=" + cheersSent
                + ", guestbooks=" + guestbooksWritten
                + ", cobwebs=" + cobwebsCleaned
                + ", layouts=" + layoutsRotated
                + ", businessSkips=" + businessSkips
                + ", failures=" + failures;
    }
}
