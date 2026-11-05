package com.triples.rougether.adminapi.activity.dto;

public enum UserActivityFeature {
    ACCESS("인증 활동", "일별 최초 인증 요청 기록과 마지막 로그인·토큰 갱신 시각입니다. 실시간 온라인 상태가 아닙니다."),
    SIGNUP("가입", "회원 생성 기록입니다."),
    ROUTINE_CREATE("루틴 생성", "루틴 생성 기록입니다. 온보딩으로 생성한 루틴도 포함합니다."),
    ROUTINE_COMPLETE("루틴 완료", "실제 완료 시각 기준으로 현재 남아 있는 완료 로그를 셉니다. 예약된 수행 날짜와 다를 수 있습니다."),
    TODO_CREATE("할 일 생성", "할 일 생성 기록입니다."),
    TODO_COMPLETE("할 일 완료", "실제 완료 시각 기준으로 현재 완료 상태인 할 일을 셉니다."),
    ROOM_DECORATE("방 꾸미기", "현재 남은 가구 배치와 배경 슬롯의 마지막 저장 기록 수입니다. 저장 횟수나 옮긴 횟수가 아닙니다."),
    AI_FURNITURE("AI 가구 요청", "제작 요청 생성 기록입니다. 업로드 대기와 실패도 포함하며 완성된 가구 수가 아닙니다."),
    GACHA("뽑기", "재화를 사용한 뽑기 결제 원장 수입니다. 뽑힌 물품 개수나 무료 뽑기 횟수가 아닙니다."),
    SHOP_PURCHASE("상점 구매", "상점 구매 결제 원장 수입니다."),
    MARKET_BUY("가구 구매", "구매자 기준 고정가 구매와 기존 거래소 체결 기록 수입니다."),
    MARKET_SELL("가구 판매", "판매자 기준 고정가 구매와 기존 거래소 체결 기록 수입니다. 판매 등록·가격 변경 시각은 포함하지 않습니다."),
    FEED_POST("피드 작성", "게시글 생성 기록입니다. 이후 삭제해도 기록이 남아 있으면 포함합니다."),
    FEED_COMMENT("댓글 작성", "댓글·답글 생성 기록입니다. 본문은 조회하지 않습니다."),
    HOUSE_JOIN("집 참여", "집의 마지막 참여 기록입니다. 온보딩 자동 참여도 포함합니다."),
    HOUSE_CHEER("집 응원", "응원을 보낸 사람 기준의 응원 기록입니다."),
    CHAT("채팅 전송", "채팅 메시지 생성 기록입니다. 메시지 내용은 조회하지 않습니다."),
    GUESTBOOK("방명록 작성", "방명록 생성 기록입니다. 방명록 내용은 조회하지 않습니다."),
    MINIGAME("미니게임 시작", "게임 실행 시작 기록입니다. 끝까지 완료하지 않은 게임도 포함합니다."),
    ATTENDANCE("출석", "출석 체크 생성 기록입니다.");

    private final String label;
    private final String description;

    UserActivityFeature(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() { return label; }
    public String description() { return description; }
}
