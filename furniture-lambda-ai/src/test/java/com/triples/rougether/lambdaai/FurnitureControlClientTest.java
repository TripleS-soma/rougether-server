package com.triples.rougether.lambdaai;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;

class FurnitureControlClientTest {
    @Test void responseAfterThirtySecondsDoesNotCauseHiddenHttpRetry() throws Exception {
        var requests=new AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{
            requests.incrementAndGet();x.getRequestBody().readAllBytes();
            try {Thread.sleep(31_000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            byte[] body="{\"status\":\"TERMINAL\",\"claim\":null}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();
        });server.start();
        try(var client=FurnitureControlClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:"+server.getAddress().getPort()))
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test"))).build()) {
            long t=System.nanoTime();var r=client.invoke(b->b.functionName("test-control"));
            assertThat(r.statusCode()).isEqualTo(200);assertThat(r.payload().asUtf8String()).contains("TERMINAL");
            assertThat(requests).hasValue(1);assertThat((System.nanoTime()-t)/1e9).isBetween(31.0,40.0);
        } finally {server.stop(0);}
    }
}
