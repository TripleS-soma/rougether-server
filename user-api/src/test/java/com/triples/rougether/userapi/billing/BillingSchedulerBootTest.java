package com.triples.rougether.userapi.billing;
import static org.assertj.core.api.Assertions.assertThat;
import com.triples.rougether.userapi.billing.service.FurnitureBillingService;
import com.triples.rougether.furniture.ai.FurnitureAiClient;
import com.triples.rougether.infra.assets.AssetStorageService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"billing.worker-enabled=true", "furniture.generation.worker-enabled=true", "furniture.generation.poll-delay=1h"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingSchedulerBootTest {
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ApplicationContext context;
    @MockitoBean FurnitureBillingService billing;
    @MockitoBean FurnitureAiClient ai;
    @MockitoBean AssetStorageService storage;
    @Test void 결제_스케줄러는_유지하고_가구_실행은_API에서_제거됨() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(context.getBean("billingTaskScheduler")).isNotSameAs(context.getBean("taskScheduler"));
        assertThat(context.containsBean("furnitureTaskScheduler")).isFalse();
    }
}
