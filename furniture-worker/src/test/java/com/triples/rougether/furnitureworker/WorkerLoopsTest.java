package com.triples.rougether.furnitureworker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.furniture.service.FurnitureGenerationWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerLoopsTest {
    @Test void 로컬_병렬수만_실행하고_종료는_진행중_작업의_완료를_기다림() throws Exception {
        var worker=mock(FurnitureGenerationWorker.class);
        var entered=new CountDownLatch(3);var release=new CountDownLatch(1);
        var current=new AtomicInteger();var peak=new AtomicInteger();var calls=new AtomicInteger();
        doAnswer(i -> {
            calls.incrementAndGet();peak.accumulateAndGet(current.incrementAndGet(),Math::max);entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } finally { current.decrementAndGet(); }
            return null;
        }).when(worker).runNext();
        var loops=new WorkerLoops(worker,new SimpleMeterRegistry(),3,Duration.ofMillis(50),Duration.ofSeconds(3));
        try(var closer=Executors.newSingleThreadExecutor()) {
            loops.start();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var stopping=closer.submit(() -> { loops.stop(); });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(loops.isRunning() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(loops.isRunning()).isFalse();assertThat(stopping.isDone()).isFalse();
            release.countDown();stopping.get(5,TimeUnit.SECONDS);
            assertThat(peak).hasValue(3);assertThat(calls).hasValue(3);
        } finally { release.countDown();loops.stop(); }
    }
    @Test void 비정상_병렬수는_기동전에_거부함() {
        assertThatThrownBy(() -> new WorkerLoops(mock(FurnitureGenerationWorker.class),new SimpleMeterRegistry(),
                100,Duration.ofSeconds(2),Duration.ofSeconds(270))).isInstanceOf(IllegalArgumentException.class);
    }
}
