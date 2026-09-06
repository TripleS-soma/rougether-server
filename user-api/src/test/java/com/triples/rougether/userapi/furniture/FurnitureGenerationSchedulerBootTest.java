package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.furniture.config.FurnitureGenerationScheduler;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "furniture.generation.worker-enabled=true",
        "furniture.generation.poll-delay=1h"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FurnitureGenerationSchedulerBootTest {
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired FurnitureGenerationScheduler scheduler;
    @MockitoBean FurnitureAiClient ai;
    @MockitoBean AssetStorageService storage;

    @Test
    void 가구_스케줄러가_활성화되어도_실제_JPA와_함께_기동한다() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(scheduler).isNotNull();
    }
}
