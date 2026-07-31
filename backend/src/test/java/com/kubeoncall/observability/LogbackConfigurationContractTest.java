package com.kubeoncall.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LogbackConfigurationContractTest {

    @Test
    void configuresBoundedJsonStdoutWithoutAsyncOrNetworkSink() throws Exception {
        URL resource = getClass().getClassLoader().getResource("logback-spring.xml");
        assertThat(resource).isNotNull();
        String xml = Files.readString(Path.of(resource.toURI()));

        assertThat(xml)
                .contains("ConsoleAppender")
                .contains("LoggingEventCompositeJsonEncoder")
                .contains("\"service\"")
                .contains("\"environment\"")
                .contains("\"release\"")
                .contains("\"instance\"")
                .contains("\"logger\"")
                .contains("\"level\"")
                .contains("\"message\"")
                .contains("<stackTrace>")
                .contains("<mdc/>")
                .doesNotContain("AsyncAppender")
                .doesNotContain("SocketAppender")
                .doesNotContain("TcpSocketAppender");
    }
}
