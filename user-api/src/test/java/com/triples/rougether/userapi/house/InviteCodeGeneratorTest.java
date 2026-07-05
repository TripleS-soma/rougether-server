package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.house.support.InviteCodeGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteCodeGeneratorTest {

    @Mock private HouseRepository houseRepository;
    @InjectMocks private InviteCodeGenerator inviteCodeGenerator;

    @Test
    void 코드는_8자이고_혼동문자를_쓰지_않는다() {
        when(houseRepository.existsByInviteCode(anyString())).thenReturn(false);

        for (int i = 0; i < 50; i++) {
            String code = inviteCodeGenerator.generate();
            assertThat(code).hasSize(8).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
            assertThat(code).doesNotContain("I", "O", "L", "0", "1");
        }
    }

    @Test
    void 충돌하면_재시도해서_다른_코드를_발급한다() {
        when(houseRepository.existsByInviteCode(anyString())).thenReturn(true, true, false);

        String code = inviteCodeGenerator.generate();

        assertThat(code).hasSize(8);
    }

    @Test
    void 연속_충돌이_한계를_넘으면_실패한다() {
        when(houseRepository.existsByInviteCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> inviteCodeGenerator.generate())
                .isInstanceOf(IllegalStateException.class);
    }
}
