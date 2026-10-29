package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Swagger UI is a developer convenience, off unless the "dev" profile turns it on
 * (springdoc.swagger-ui.enabled). It used to be served publicly in every deployment, and the
 * DOMPurify bundled in it is what owasp-suppressions.xml now suppresses on the strength of it never
 * being served. This proves it is not, with the default configuration every deployment starts from,
 * and that the OpenAPI document itself, the contract, still is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
class SwaggerUiExposureIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private HttpResponse<String> get(final String path) throws Exception {
    return httpClient.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theSwaggerUiPageIsNotServedByDefault() throws Exception {
    assertThat(get("/swagger-ui.html").statusCode()).isEqualTo(404);
    assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(404);
  }

  @Test
  void theBundledScriptsAreNotServedByDefaultEither() throws Exception {
    assertThat(get("/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(404);
  }

  @Test
  void theOpenApiDocumentIsStillServed() throws Exception {
    final HttpResponse<String> response = get("/v3/api-docs");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"openapi\"");
  }
}
