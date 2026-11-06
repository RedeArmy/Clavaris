package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;

/**
 * TD-PROC-024: with nothing configured, no span is sent anywhere. The exporter used to be on by
 * default and, on a host with no Zipkin, logged a WARN for every batch of spans. Tracing itself
 * must stay on: the trace id is in the logs and in the event outbox whether or not anything is
 * exported.
 */
@SpringBootTest
@Import(TestMailSenderConfig.class)
@Testcontainers
class TracingExportDisabledByDefaultIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private ApplicationContext context;

  @Test
  void theSpanExportIsAsConfigured() {
    assertThat(context.getBeanNamesForType(AsyncZipkinSpanHandler.class))
        .as("nothing may report spans unless asked to")
        .isEmpty();
    assertThat(context.getBeanNamesForType(Tracer.class))
        .as("tracing itself stays on, so trace ids still reach the logs and the outbox")
        .isNotEmpty();
  }
}
