package com.triples.rougether.adminapi.activity.service;

import com.sun.management.ThreadMXBean;
import com.triples.rougether.adminapi.AdminApiApplication;
import com.triples.rougether.adminapi.activity.dto.UserActivityFeature;
import com.triples.rougether.adminapi.retention.service.AdminRetentionMetricsService;
import com.triples.rougether.domain.activity.repository.AdminUserActivityRepository;
import com.triples.rougether.domain.retention.repository.AdminRetentionMetricRepository;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 로컬 전용 고정 데이터 비교. 운영 연결이나 강제 GC를 운영에서 실행하지 않는다. */
public class AdminMemoryBenchmark {
    static final LocalDate TODAY = LocalDate.of(2030, 8, 20);
    static final Instant NOW = TODAY.atTime(13, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
    @Configuration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary Clock benchmarkClock() { return Clock.fixed(NOW, ZoneId.of("Asia/Seoul")); }
    }

    public static void main(String[] args) throws Exception {
        var app = new SpringApplication(AdminApiApplication.class, FixedClock.class);
        try (var context = app.run(
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:13384/admin_memory?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "--spring.datasource.username=root", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
                "--admin.seed.enabled=false", "--firebase.credentials-path=",
                "--server.address=127.0.0.1", "--server.port=0",
                "--admin.notification-campaigns.dispatch-enabled=false", "--logging.level.root=WARN")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            if (args[0].equals("seed")) { seed(jdbc); return; }
            Path output = Path.of(args[0]);
            Files.createDirectories(output);
            ObjectMapper json = context.getBean(ObjectMapper.class);
            var retention = context.getBean(AdminRetentionMetricsService.class);
            var repo = context.getBean(AdminRetentionMetricRepository.class);
            var activityRepo = context.getBean(AdminUserActivityRepository.class);
            Clock clock = context.getBean(Clock.class);
            // 두 버전의 API DTO 전체를 비교한다. 데이터와 시각은 고정한다.
            Map<String, Object> responses = new TreeMap<>();
            for (int days : new int[]{1, 35, 90}) responses.put("retention-" + days, retention.getMetrics(days));
            var warmReader = new AdminUserActivityReader(activityRepo, clock, Duration.ofSeconds(15), () -> 0L);
            var activity = new AdminUserActivityService(warmReader, clock);
            for (int day : new int[]{0, 1, 7}) {
                for (int page : new int[]{0, 2}) {
                    responses.put("activity-" + day + "-" + page,
                            activity.getActivities(TODAY.minusDays(day), "", null, false, page, 20));
                }
                responses.put("filtered-" + day,
                        activity.getActivities(TODAY.minusDays(day), "bench-1", UserActivityFeature.ROUTINE_COMPLETE, true, 0, 20));
            }
            Files.writeString(output.resolve("responses.json"), json.writeValueAsString(responses));
            warmReader = null;
            activity = null;
            responses = null;
            long resultRows;
            try {
                var method = repo.getClass().getMethod("findCompletionDays", Instant.class, Instant.class);
                resultRows = ((List<?>) method.invoke(repo, TODAY.minusDays(29).atStartOfDay(clock.getZone()).toInstant(), NOW)).size();
            } catch (NoSuchMethodException baseline) {
                var method = repo.getClass().getMethod("findCompletionEvents", Instant.class, Instant.class);
                resultRows = ((List<?>) method.invoke(repo, TODAY.minusDays(29).atStartOfDay(clock.getZone()).toInstant(), NOW)).size();
            }
            for (int i = 0; i < 3; i++) retention.getMetrics(35);
            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().threadId();
            long before = threads.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            for (int i = 0; i < 5; i++) retention.getMetrics(35);
            long retentionAllocated = (threads.getThreadAllocatedBytes(thread) - before) / 5;
            double retentionMs = (System.nanoTime() - started) / 5e6;
            long activityColdAllocated = measureCold(activityRepo, clock, threads, thread);
            // 같은 DB 조회/변환 경로를 먼저 충분히 실행한 뒤 캐시가 살아 있는 힙과 해제 후 힙을 비교한다.
            var ticker = new AtomicLong();
            var reader = new AdminUserActivityReader(activityRepo, clock, Duration.ofSeconds(15), ticker::get);
            var cacheField = AdminUserActivityReader.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            var cache = (Map<?, ?>) cacheField.get(reader);
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < 8; i++) reader.read(TODAY.minusDays(i));
                cache.clear();
                settledHeap();
            }
            int cacheCopies = 8;
            List<AdminUserActivityReader> readers = new ArrayList<>();
            for (int copy = 0; copy < cacheCopies; copy++) readers.add(new AdminUserActivityReader(
                    activityRepo, clock, Duration.ofSeconds(15), ticker::get));
            reader = readers.getFirst();
            before = threads.getThreadAllocatedBytes(thread);
            for (var copy : readers) for (int i = 0; i < 8; i++) copy.read(TODAY.minusDays(i));
            long cacheAllocated = (threads.getThreadAllocatedBytes(thread) - before) / cacheCopies;
            var cachedService = new AdminUserActivityService(reader, clock);
            for (int i = 0; i < 3; i++) cachedService.getActivities(TODAY, "", null, false, 0, 20);
            before = threads.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 10; i++) cachedService.getActivities(TODAY, "", null, false, 0, 20);
            long activityHitAllocated = (threads.getThreadAllocatedBytes(thread) - before) / 10;
            long populated = settledHeap();
            ticker.set(Duration.ofSeconds(15).toNanos());
            for (var copy : readers) copy.read(TODAY.minusDays(8));
            long expired = settledHeap();
            int cacheEntries = ((Map<?, ?>) cacheField.get(reader)).size();
            for (var copy : readers) ((Map<?, ?>) cacheField.get(copy)).clear();
            long cleared = settledHeap();
            // 전체 힙 차이가 음수면 절감량으로 보고하지 않고 측정 실패로 처리한다.
            if (populated < cleared) throw new IllegalStateException("Unstable heap delta: " + populated + "/" + expired + "/" + cleared);
            Map<String, Object> metrics = new TreeMap<>();
            metrics.put("completionRows", resultRows);
            metrics.put("retentionAllocatedBytesPerRequest", retentionAllocated);
            metrics.put("retentionMeanMs", retentionMs);
            metrics.put("cacheFillAllocatedBytes", cacheAllocated);
            metrics.put("cacheCopies", cacheCopies);
            metrics.put("cacheRetainedBytes", (populated - cleared) / cacheCopies);
            metrics.put("cacheAfterExpiryBytes", expired >= cleared ? (expired - cleared) / cacheCopies : null);
            metrics.put("activityHitAllocatedBytesPerRequest", activityHitAllocated);
            metrics.put("activityColdAllocatedBytesPerRequest", activityColdAllocated);
            metrics.put("cacheEntriesAfterExpiry", cacheEntries);
            metrics.put("datasetUsers", jdbc.queryForObject("select count(*) from users where id >= 100000", Long.class));
            metrics.put("datasetLogs", jdbc.queryForObject("select count(*) from routine_logs", Long.class));
            Files.writeString(output.resolve("metrics.json"), json.writeValueAsString(metrics));
            System.out.println("BENCHMARK " + json.writeValueAsString(metrics));
        }
    }

    static long measureCold(AdminUserActivityRepository repo, Clock clock, ThreadMXBean threads, long thread) {
        long before = 0;
        for (int i = 0; i < 8; i++) {
            if (i == 3) before = threads.getThreadAllocatedBytes(thread);
            var reader = new AdminUserActivityReader(repo, clock, Duration.ofSeconds(15), () -> 0L);
            new AdminUserActivityService(reader, clock).getActivities(TODAY, "", null, false, 0, 20);
        }
        return (threads.getThreadAllocatedBytes(thread) - before) / 5;
    }

    static long settledHeap() throws Exception {
        System.gc();
        Thread.sleep(150);
        System.gc();
        Thread.sleep(150);
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static void seed(JdbcTemplate jdbc) {
        if (jdbc.queryForObject("select count(*) from users where id >= 100000", Long.class) > 0) { seedBoundary(jdbc); return; }
        jdbc.execute("create table bench_numbers(n int primary key)");
        jdbc.batchUpdate("insert into bench_numbers values (?)", java.util.stream.IntStream.range(0, 500)
                .mapToObj(i -> new Object[]{i}).toList());
        jdbc.execute("insert into users(id,nickname,created_at,updated_at,is_bot,is_ai,deleted_at) "
                + "select 100000+n,concat('bench-',n),timestampadd(day,-mod(n,35),'2030-08-19 15:00:00'),"
                + "'2030-08-20 04:00:00',n<5,n>=5 and n<10,if(n>=10 and n<15,'2030-08-20 00:00:00',null) from bench_numbers");
        jdbc.execute("insert into routines(id,user_id,title,auth_type,status,repeat_type,repeat_days,starts_on,created_at,updated_at) "
                + "select 200000+u.n*10+r.n,100000+u.n,'benchmark','CHECK','ACTIVE','DAILY','[]','2030-07-01',"
                + "'2030-07-01 00:00:00','2030-07-01 00:00:00' from bench_numbers u join bench_numbers r on r.n<10");
        jdbc.execute("insert into routine_logs(routine_id,routine_date,status,completed_at,reward_currency_type,reward_amount,created_at,growth_reward_amount) "
                + "select r.id,date(timestampadd(day,-d.n,'2030-08-20')),'COMPLETED',"
                + "timestampadd(minute,mod(r.id,10),timestampadd(day,-d.n,'2030-08-20 03:00:00')),'COIN',10,"
                + "timestampadd(day,-d.n,'2030-08-20 03:00:00'),0 from routines r join bench_numbers d on d.n<30");
        jdbc.execute("insert into user_daily_activity(user_id,activity_date,created_at) "
                + "select 100000+u.n,date(timestampadd(day,-d.n,'2030-08-20')),'2030-08-20 03:00:00' "
                + "from bench_numbers u join bench_numbers d on d.n<35");
        seedBoundary(jdbc);
        System.out.println("SEEDED 500 users / boundary fixture / 17500 activity dates");
    }
    static void seedBoundary(JdbcTemplate jdbc) {
        if (jdbc.queryForObject("select nickname from users where id=100499", String.class).equals("bench-boundary-utc")) return;
        jdbc.update("delete from routine_logs where routine_id between 204990 and 204999");
        jdbc.update("update users set nickname='bench-boundary-utc' where id=100499");
        String[] times = {"2030-07-21 14:59:59", "2030-07-21 15:00:00", "2030-07-21 16:00:00",
                "2030-08-16 14:59:59", "2030-08-16 15:00:00", "2030-08-20 03:59:59",
                "2030-08-20 04:00:00", "2030-08-20 04:00:01"};
        for (int i = 0; i < times.length; i++) jdbc.update(
                "insert into routine_logs(routine_id,routine_date,status,completed_at,reward_currency_type,reward_amount,created_at,growth_reward_amount) values (204990,?,'COMPLETED',?,'COIN',10,?,0)",
                LocalDate.of(2030, 7, 1).plusDays(i), java.sql.Timestamp.from(Instant.parse(times[i].replace(' ', 'T') + "Z")), java.sql.Timestamp.from(Instant.parse(times[i].replace(' ', 'T') + "Z")));
    }

}
