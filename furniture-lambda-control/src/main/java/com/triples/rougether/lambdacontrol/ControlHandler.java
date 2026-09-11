package com.triples.rougether.lambdacontrol;

import com.amazonaws.services.lambda.runtime.*;
import com.triples.rougether.furniture.lambda.FurnitureLambdaControl;
import com.triples.rougether.furniture.lambda.FurnitureLambdaProtocol.Command;
import com.triples.rougether.furniture.service.FurnitureGenerationTransactions;
import com.triples.rougether.common.furniture.FurniturePreprocessProtocol;
import java.io.*;
import org.springframework.boot.*;
import tools.jackson.databind.json.JsonMapper;

public class ControlHandler implements RequestStreamHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final java.util.function.Supplier<org.springframework.context.ApplicationContext> application;
    public ControlHandler() { application = () -> Holder.APP; }
    ControlHandler(org.springframework.context.ApplicationContext application) { this.application = () -> application; }
    private static class Holder {
        static final org.springframework.context.ConfigurableApplicationContext APP = new SpringApplicationBuilderHelper().start();
    }
    private static class SpringApplicationBuilderHelper {
        org.springframework.context.ConfigurableApplicationContext start() {
            var app = new SpringApplication(ControlApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            return app.run();
        }
    }
    @Override public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        byte[] body = input.readNBytes(32_769);
        if (body.length > 32_768) throw new IllegalArgumentException("제어 명령 크기 초과");
        var tree = JSON.readTree(body);
        if ("PREPROCESS".equals(tree.path("kind").asString())) {
            JSON.writeValue(output, application.get().getBean(FurnitureGenerationTransactions.class)
                    .preprocess(JSON.treeToValue(tree, FurniturePreprocessProtocol.Command.class)));
        } else {
            JSON.writeValue(output, application.get().getBean(FurnitureLambdaControl.class).handle(JSON.treeToValue(tree, Command.class)));
        }
    }
}
