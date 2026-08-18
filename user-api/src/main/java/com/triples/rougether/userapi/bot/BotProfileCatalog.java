package com.triples.rougether.userapi.bot;

import java.util.List;

// 동거 봇 6명의 정본 카탈로그(#307). 코드 상수라 배포 없이 바뀌지 않으며, 시드는 botKey 기준으로 멱등하게 동기화한다.
// 닉네임·bio 는 금칙어를 피한 무난한 문구로 두고, 활동 프로필은 MORNING/EVENING/SPREAD 각 2명씩 배분한다.
// 대표 캐릭터 코드는 카탈로그에 그 코드가 활성으로 있을 때만 쓰이고, 없으면 시드가 활성 캐릭터를 순번으로 배정한다.
public final class BotProfileCatalog {

    public static final List<BotProfile> PROFILES = List.of(
            new BotProfile("bot-dawn", "새벽이", "아침 6시에 눈 뜨는 편이에요. 같이 하루 열어요.",
                    List.of("exercise", "habit"), "cat", BotActivityProfile.MORNING,
                    "아침 루틴", "#F4B183",
                    List.of("기상 후 물 한 잔", "가벼운 스트레칭", "오늘 할 일 3개 적기", "아침 산책 10분")),
            new BotProfile("bot-focus", "집중이", "공부는 짧게 자주. 오늘도 25분만 앉아볼게요.",
                    List.of("study", "job"), "rabbit", BotActivityProfile.MORNING,
                    "공부", "#8FAADC",
                    List.of("뽀모도로 1세트", "영어 단어 20개", "어제 배운 것 복습", "책상 정리")),
            new BotProfile("bot-moon", "달무리", "밤에 조용히 하루를 정리하는 걸 좋아해요.",
                    List.of("sleep", "reading"), "bear", BotActivityProfile.EVENING,
                    "저녁 루틴", "#B4A7D6",
                    List.of("책 10쪽 읽기", "내일 준비물 챙기기", "폰 내려놓고 스트레칭", "12시 전에 잠들기")),
            new BotProfile("bot-mint", "민트", "퇴근 후 운동은 저와 함께. 꾸준함이 전부예요.",
                    List.of("exercise", "sleep"), "dog", BotActivityProfile.EVENING,
                    "운동", "#93C47D",
                    List.of("홈트 20분", "물 2리터 마시기", "계단으로 올라가기", "저녁 8시 이후 금식")),
            new BotProfile("bot-latte", "라떼", "천천히 오래. 작은 습관을 하나씩 쌓아요.",
                    List.of("habit", "organize"), "cat", BotActivityProfile.SPREAD,
                    "생활 습관", "#FFD966",
                    List.of("침대 정리", "설거지 바로 하기", "5분 청소", "감사한 일 1개 적기")),
            new BotProfile("bot-pine", "솔이", "취준 중. 매일 조금씩, 서로 응원해요.",
                    List.of("job", "study"), "fox", BotActivityProfile.SPREAD,
                    "취업 준비", "#6FA8DC",
                    List.of("채용 공고 확인", "자소서 한 문단", "코딩 문제 1개", "산책하며 리프레시")));

    public static final int BOT_COUNT = PROFILES.size();

    private BotProfileCatalog() {
    }
}
