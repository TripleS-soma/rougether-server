package com.triples.rougether.furnitureworker;

import com.triples.rougether.furniture.service.FurnitureGenerationWorker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class WorkerLoops implements SmartLifecycle {
    private static final Logger log=LoggerFactory.getLogger(WorkerLoops.class);
    private final FurnitureGenerationWorker worker;
    private final int concurrency;
    private final long pollMillis;
    private final long drainMillis;
    private final AtomicInteger active=new AtomicInteger();
    private volatile boolean running;
    private ScheduledExecutorService pool;
    public WorkerLoops(FurnitureGenerationWorker worker, MeterRegistry metrics,
            @Value("${furniture.worker.concurrency:1}") int concurrency,
            @Value("${furniture.worker.poll-delay:2s}") Duration poll,
            @Value("${furniture.worker.shutdown-timeout:270s}") Duration drain) {
        if (concurrency<1 || concurrency>40 || poll.toMillis()<50 || drain.toSeconds()<1 || drain.toSeconds()>360)
            throw new IllegalArgumentException("가구 워커 실행 설정 오류");
        this.worker=worker; this.concurrency=concurrency; this.pollMillis=poll.toMillis(); this.drainMillis=drain.toMillis();
        metrics.gauge("furniture.worker.active",active);
    }
    @Override public synchronized void start() {
        if (running) return;
        running=true;
        pool=Executors.newScheduledThreadPool(concurrency+1,Thread.ofPlatform().name("furniture-worker-",0).factory());
        for(int i=0;i<concurrency;i++) pool.scheduleWithFixedDelay(() -> execute(false),0,pollMillis,TimeUnit.MILLISECONDS);
        pool.scheduleWithFixedDelay(() -> execute(true),0,60000,TimeUnit.MILLISECONDS);
    }
    private void execute(boolean maintenance) {
        if (!running) return;
        if (!maintenance) active.incrementAndGet();
        try { if (maintenance) worker.maintain(); else worker.runNext(); }
        catch (RuntimeException e) { log.warn("가구 워커 순회 실패 - 다음 순회에서 확인",e); }
        finally { if (!maintenance) active.decrementAndGet(); }
    }
    @Override public void stop() {
        ScheduledExecutorService executor;
        synchronized(this) { running=false; executor=pool; }
        if(executor==null)return;
        executor.shutdown();
        try {
            if(!executor.awaitTermination(drainMillis,TimeUnit.MILLISECONDS)) {
                log.warn("가구 워커 종료 대기 초과 - 미완료 작업은 lease 만료로 정산");
                executor.shutdownNow();
            }
        } catch(InterruptedException e) { executor.shutdownNow();Thread.currentThread().interrupt(); }
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
}
