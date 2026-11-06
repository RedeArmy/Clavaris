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
 * TD-PROC-024: the other direction. docker-compose.observability.yml (and a deployment with its own
 * collector) sets CLAVARIS_ZIPKIN_ENABLED, so the switch must really turn the export on, or the
 * observability stack would go quiet without a sound.
 */
@SpringBootTest(properties = "CLAVARIS_ZIPKIN_ENABLED=true")
@Import(TestMailSenderConfig.class)
@Testcontainers
class TracingExportEnabledOnRequestIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private ApplicationContext context;

  @Test
  void theSpanExportIsAsConfigured() {
    assertThat(context.getBeanNamesForType(AsyncZipkinSpanHandler.class))
        .as("spans are reported when the export is switched on")
        .hasSize(1);
    assertThat(context.getBeanNamesForType(Tracer.class)).isNotEmpty();
  }
}
