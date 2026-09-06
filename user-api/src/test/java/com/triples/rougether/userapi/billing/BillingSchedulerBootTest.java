package com.triples.rougether.userapi.billing;
import static org.assertj.core.api.Assertions.assertThat;
import com.triples.rougether.userapi.billing.service.FurnitureBillingService;
import com.triples.rougether.userapi.furniture.ai.FurnitureAiClient;
import com.triples.rougether.userapi.global.storage.AssetStorageService;
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
    @Test void 결제_가구_기존_스케줄러가_분리되고_JPA와_함께_기동() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(context.getBean("billingTaskScheduler")).isNotSameAs(context.getBean("taskScheduler"))
                .isNotSameAs(context.getBean("furnitureTaskScheduler"));
    }
}
