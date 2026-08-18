package com.triples.rougether.userapi.bot;

import java.time.LocalTime;
import java.util.List;

// 동거 봇의 활동 시간대(KST). 활동 스케줄러(#310)는 이 창 안의 틱에서만 행동한다.
public enum BotActivityProfile {

    MORNING(List.of(
            new Window(LocalTime.of(6, 0), LocalTime.of(9, 0)),
            new Window(LocalTime.of(12, 0), LocalTime.of(13, 0)))),
    EVENING(List.of(
            new Window(LocalTime.of(18, 0), LocalTime.of(23, 0)))),
    SPREAD(List.of(
            new Window(LocalTime.of(8, 0), LocalTime.of(22, 0))));

    private final List<Window> windows;

    BotActivityProfile(List<Window> windows) {
        this.windows = windows;
    }

    public List<Window> windows() {
        return windows;
    }

    // [start, end) 반열림 구간. 자정을 넘는 창은 없다.
    public boolean isActiveAt(LocalTime time) {
        return windows.stream().anyMatch(window -> window.contains(time));
    }

    public record Window(LocalTime start, LocalTime end) {

        public boolean contains(LocalTime time) {
            return !time.isBefore(start) && time.isBefore(end);
        }
    }
}
