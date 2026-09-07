package com.triples.rougether.adminapi.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.adminapi.attendance.dto.AttendanceEventCreateRequest;
import com.triples.rougether.adminapi.attendance.dto.AttendanceEventCreateResponse;
import com.triples.rougether.adminapi.attendance.error.AttendanceEventAdminException;
import com.triples.rougether.adminapi.attendance.service.AttendanceEventAdminService;
import com.triples.rougether.domain.attendance.repository.AttendanceEventRepository;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.Theme;
import com.triples.rougether.domain.shop.repository.ItemRepository;
import com.triples.rougether.domain.shop.repository.ThemeRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AttendanceEventAdminServiceTest {

    @Autowired private AttendanceEventAdminService attendanceEventAdminService;
    @Autowired private AttendanceEventRepository attendanceEventRepository;
    @Autowired private ThemeRepository themeRepository;
    @Autowired private ItemRepository itemRepository;

    private Item rewardItem;

    @BeforeEach
    void setUp() {
        Theme theme = themeRepository.save(new Theme(
                "event_admin", "이벤트", "themes/event-admin.png", true));
        rewardItem = itemRepository.save(new Item(
                theme, "furniture", "positioned", null, null,
                "출석 트로피", null, null, "items/events/trophy.png", true, true));
    }

    @Test
    void 열흘_출석_코인과_가구_설정을_저장한다() {
        AttendanceEventCreateResponse response = attendanceEventAdminService.create(request(5));

        assertThat(response.id()).isNotNull();
        assertThat(response.targetDays()).isEqualTo(10);
        assertThat(response.dailyCoinAmount()).isEqualTo(30);
        assertThat(response.bonusDay()).isEqualTo(5);
        assertThat(response.bonusCoinAmount()).isEqualTo(50);
        assertThat(attendanceEventRepository.findById(response.id())).isPresent();
    }

    @Test
    void 보너스_일차가_목표를_벗어나면_거부한다() {
        assertThatThrownBy(() -> attendanceEventAdminService.create(request(11)))
                .isInstanceOf(AttendanceEventAdminException.class)
                .satisfies(exception -> assertThat(((AttendanceEventAdminException) exception).code())
                        .isEqualTo("ATTENDANCE_EVENT_BONUS_DAY_INVALID"));
    }

    @Test
    void 가구를_지정하지않은_7일이벤트는_생성권1회를_지급한다() {
        var response = attendanceEventAdminService.create(new AttendanceEventCreateRequest(
                "ATTENDANCE_AI_7D", "7일 출석", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9),
                7, 30, 5, 50, null));
        assertThat(response.targetDays()).isEqualTo(7);
        assertThat(response.rewardItemId()).isNull();
        assertThat(response.generationCreditAmount()).isEqualTo(1);
        assertThat(attendanceEventRepository.findActiveOn(LocalDate.of(2026, 9, 10)))
                .extracting(event -> event.getId()).contains(response.id());
    }

    @Test
    void 생성권이벤트의_목표일은_7일로_고정한다() {
        assertThatThrownBy(() -> attendanceEventAdminService.create(new AttendanceEventCreateRequest(
                "ATTENDANCE_AI_10D", "잘못된 목표", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9),
                10, 30, 5, 50, null)))
                .isInstanceOf(AttendanceEventAdminException.class)
                .satisfies(ex -> assertThat(((AttendanceEventAdminException) ex).code())
                        .isEqualTo("ATTENDANCE_GENERATION_TARGET_INVALID"));
    }

    private AttendanceEventCreateRequest request(int bonusDay) {
        return new AttendanceEventCreateRequest(
                "ATTENDANCE_10D_2026", "10일 연속 출석",
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 14),
                10, 30, bonusDay, 50, rewardItem.getId());
    }
}
