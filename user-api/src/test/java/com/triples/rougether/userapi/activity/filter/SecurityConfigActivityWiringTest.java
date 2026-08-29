package com.triples.rougether.userapi.activity.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.userapi.activity.service.UserDailyActivityRecorder;
import com.triples.rougether.userapi.global.config.SecurityConfig;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class SecurityConfigActivityWiringTest {

    @Test
    void 활동_기록기는_SecurityConfig의_필수_생성자_의존성이다() throws Exception {
        var field = SecurityConfig.class.getDeclaredField("userDailyActivityRecorder");

        assertThat(field.getType()).isEqualTo(UserDailyActivityRecorder.class);
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
    }
}
