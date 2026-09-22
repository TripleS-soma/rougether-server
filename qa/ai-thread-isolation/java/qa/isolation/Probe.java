package qa.isolation;

import com.sun.net.httpserver.HttpServer;
import com.triples.rougether.infra.llm.*;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

// 실제 제품과 같은 Tomcat/Spring MVC 및 기존 EmbeddingClient 사용. DB/인증/제품 Controller는 제외한 기전 실험.
public class Probe {
    static final String TOKEN = "isolation-test-token-12345678901234567890";
    static String providerUrl;
    static String remoteTwoUrl;
    static String remoteWideUrl;
    static final AtomicInteger aiActive = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int metricsPort = Integer.parseInt(args[1]);
        providerUrl = args[2]; remoteTwoUrl = args[3]; remoteWideUrl = args[4];
        var tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("rougether-ai-probe-").toString());
        tomcat.setPort(port);
        var connector = tomcat.getConnector();
        connector.setProperty("address", "127.0.0.1");
        connector.setProperty("maxThreads", "8");
        connector.setProperty("minSpareThreads", "8");
        connector.setProperty("maxConnections", "256");
        connector.setProperty("acceptCount", "256");
        var context = tomcat.addContext("", Files.createTempDirectory("rougether-ai-web-").toString());
        context.setParentClassLoader(Probe.class.getClassLoader());
        var spring = new AnnotationConfigWebApplicationContext();
        spring.register(Config.class);
        var wrapper = Tomcat.addServlet(context, "mvc", new DispatcherServlet(spring));
        wrapper.setAsyncSupported(true);
        wrapper.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "mvc");
        tomcat.start();
        var metrics = HttpServer.create(new InetSocketAddress("127.0.0.1", metricsPort), 0);
        var metricsExecutor = Executors.newSingleThreadExecutor();
        metrics.setExecutor(metricsExecutor);
        metrics.createContext("/metrics", exchange -> {
            var protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
            var pool = (org.apache.tomcat.util.threads.ThreadPoolExecutor) protocol.getExecutor();
            List<Map<String, Object>> threads = new ArrayList<>();
            for (var thread : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
                if (thread.getThreadName().startsWith("http-nio-") && thread.getThreadName().contains("-exec-")) {
                    threads.add(Map.of("name", thread.getThreadName(), "state", thread.getThreadState().name(),
                        "stack", Arrays.stream(thread.getStackTrace()).limit(18).map(Object::toString).toList()));
                }
            }
            byte[] body = JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "tomcatBusy", pool.getActiveCount(), "tomcatMax", pool.getMaximumPoolSize(),
                "aiActive", aiActive.get(), "threads", threads));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        metrics.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            metrics.stop(0); metricsExecutor.shutdownNow();
            try { tomcat.stop(); tomcat.destroy(); } catch (Exception ignored) { }
            spring.close();
        }));
        tomcat.getServer().await();
    }

    @Configuration
    @EnableWebMvc
    static class Config {
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }

    @RestController
    static class Endpoints {
        final Semaphore permits = new Semaphore(2);
        final LlmProperties properties = new LlmProperties(providerUrl + "/v1", "probe-chat", "probe-provider-key",
            Duration.ofSeconds(10), null, 800, true, "low", 0, Duration.ZERO, "probe-embedding", 2);
        final EmbeddingClient direct = new OpenAiCompatibleEmbeddingClient(properties);
        final EmbeddingClient remoteTwo = new AiServiceClient(
            new AiServiceProperties(true, remoteTwoUrl, TOKEN, Duration.ofSeconds(95), false), properties);
        final EmbeddingClient remoteWide = new AiServiceClient(
            new AiServiceProperties(true, remoteWideUrl, TOKEN, Duration.ofSeconds(95), false), properties);
        final HttpClient async = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

        @GetMapping("/normal")
        public Map<String, Object> normal() { return Map.of("ok", true); }

        @GetMapping("/ai/{mode}")
        public Object ai(@PathVariable("mode") String mode) {
            if (mode.equals("async-direct")) {
                var result = new DeferredResult<Map<String, Object>>(15000L);
                var request = HttpRequest.newBuilder(URI.create(providerUrl + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer probe-provider-key")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"probe-embedding\",\"input\":[\"probe\"],\"dimensions\":2,\"encoding_format\":\"float\"}"))
                    .build();
                aiActive.incrementAndGet();
                var pending = async.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                result.onTimeout(() -> { pending.cancel(true); result.setResult(outcome(false)); });
                pending.whenComplete((response, failure) -> {
                    aiActive.decrementAndGet();
                    boolean valid = false;
                    if (failure == null && response.statusCode() == 200) {
                        try {
                            var data = JsonMapper.builder().build().readTree(response.body()).path("data");
                            valid = data.size() == 1 && data.get(0).path("index").asInt() == 0
                                && data.get(0).path("embedding").size() == 2;
                        } catch (RuntimeException ignored) { }
                    }
                    result.setResult(outcome(valid));
                });
                return result;
            }
            boolean bounded = mode.equals("sync-direct-bounded");
            if (bounded && !permits.tryAcquire()) return outcome(false);
            aiActive.incrementAndGet();
            try {
                var client = switch (mode) {
                    case "sync-direct", "sync-direct-bounded" -> direct;
                    case "sync-remote-wide" -> remoteWide;
                    case "sync-remote-two" -> remoteTwo;
                    default -> throw new IllegalArgumentException("unknown mode");
                };
                client.embed(List.of("probe"));
                return outcome(true);
            } catch (LlmException failure) {
                return outcome(false);
            } finally {
                aiActive.decrementAndGet();
                if (bounded) permits.release();
            }
        }

        static Map<String, Object> outcome(boolean applied) {
            // 제품 유사도 API처럼 HTTP 200 폴백도 별도로 집계함.
            return Map.of("embeddingApplied", applied);
        }
    }
}
