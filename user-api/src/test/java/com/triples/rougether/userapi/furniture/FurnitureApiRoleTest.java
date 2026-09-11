package com.triples.rougether.userapi.furniture;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

@SpringBootTest(properties="furniture.generation.worker-enabled=true")
class FurnitureApiRoleTest {
    @Autowired ScheduledAnnotationBeanPostProcessor schedules;
    @Test void 구버전_워커_플래그를_켜도_API에는_가구_실행스케줄이_없음() {
        assertThat(schedules.getScheduledTasks()).noneMatch(t -> t.toString().contains("FurnitureGeneration"));
    }
}
