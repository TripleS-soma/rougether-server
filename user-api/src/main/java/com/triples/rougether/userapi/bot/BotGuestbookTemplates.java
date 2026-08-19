package com.triples.rougether.userapi.bot;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Random;

// 동거 봇 방명록 템플릿 30개(#310). {nickname}=방 주인 닉네임, {weekday}=요일, {count}=방 주인의 오늘 완료 개수.
// LLM 없이 치환만 한다. 금칙어 검사는 GuestbookService.write 가 그대로 수행하며, 템플릿 자체는 테스트에서 검사한다.
public final class BotGuestbookTemplates {

    // 완료 개수와 무관한 문구(20개)
    static final List<String> GENERAL = List.of(
            "{nickname}님 방 구경 왔어요! {weekday}도 화이팅이에요.",
            "지나가다 들렀어요. {nickname}님 방 분위기 좋네요 :)",
            "{weekday}이네요! 오늘 루틴도 같이 해봐요, {nickname}님.",
            "{nickname}님, 요즘 잘 지내시죠? 저도 꾸준히 하는 중이에요.",
            "방명록 하나 남기고 가요. {nickname}님 오늘도 파이팅!",
            "{weekday} 아침(혹은 저녁)도 무사히! {nickname}님 응원합니다.",
            "{nickname}님 방에 놀러 왔어요. 가구 배치가 아늑하네요.",
            "우리 집 사람들 다 같이 잘 해보자고요! {nickname}님도요.",
            "{nickname}님, 작은 습관이 큰 변화를 만든대요. 같이 가요.",
            "오늘 {weekday}인데 벌써 한 주가 이렇게 가네요. {nickname}님 힘내요!",
            "{nickname}님 방 들렀다 갑니다. 다음엔 우리 방에도 놀러 오세요.",
            "쉬엄쉬엄 해도 괜찮아요. {nickname}님 페이스대로!",
            "{nickname}님, 물 한 잔 마시고 시작해요. 저도 지금 마셨어요.",
            "{weekday}의 {nickname}님께 응원 한 스푼 두고 갑니다.",
            "{nickname}님 방 볼 때마다 저도 정리하고 싶어져요.",
            "오늘 하루도 수고 많았어요, {nickname}님. 내일도 같이!",
            "{nickname}님, 이번 주도 우리 집 미션 같이 채워봐요.",
            "잠깐 들렀어요. {nickname}님 방 조명이 포근하네요.",
            "{weekday}엔 {weekday}답게, 무리하지 말고 하나만 해도 충분해요, {nickname}님.",
            "{nickname}님 덕분에 우리 집이 더 활기차요. 고마워요!");

    // 오늘 완료 개수(1개 이상)를 언급하는 문구(10개)
    static final List<String> WITH_COUNT = List.of(
            "{nickname}님 오늘 벌써 {count}개나 완료하셨네요! 대단해요.",
            "{count}개 완료 확인했어요. {nickname}님 {weekday} 잘 보내고 계시네요.",
            "오늘 {count}개 체크! {nickname}님 리듬 좋아요.",
            "{nickname}님 {count}개 완료 축하해요. 저도 따라갈게요!",
            "{weekday}에 {count}개라니, {nickname}님 멋져요.",
            "{count}개 완료한 {nickname}님께 박수 짝짝짝!",
            "{nickname}님 오늘 {count}개 해내셨군요. 남은 하루도 가볍게!",
            "{count}개 완료 보고 자극받고 갑니다, {nickname}님.",
            "{nickname}님, {count}개 완료 응원해요. 꾸준함이 최고예요.",
            "오늘의 {count}개, {nickname}님 스스로 칭찬해 주세요!");

    public static final int TEMPLATE_COUNT = GENERAL.size() + WITH_COUNT.size();

    private BotGuestbookTemplates() {
    }

    // 완료 개수가 0이면 GENERAL 만, 1개 이상이면 전체 풀에서 고른다.
    public static String render(Random random, String nickname, DayOfWeek weekday, int completedCount) {
        List<String> pool = completedCount > 0 ? all() : GENERAL;
        String template = pool.get(random.nextInt(pool.size()));
        return template
                .replace("{nickname}", nickname == null || nickname.isBlank() ? "집 친구" : nickname)
                .replace("{weekday}", koreanWeekday(weekday))
                .replace("{count}", Integer.toString(completedCount));
    }

    public static List<String> all() {
        return java.util.stream.Stream.concat(GENERAL.stream(), WITH_COUNT.stream()).toList();
    }

    static String koreanWeekday(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "월요일";
            case TUESDAY -> "화요일";
            case WEDNESDAY -> "수요일";
            case THURSDAY -> "목요일";
            case FRIDAY -> "금요일";
            case SATURDAY -> "토요일";
            case SUNDAY -> "일요일";
        };
    }
}
