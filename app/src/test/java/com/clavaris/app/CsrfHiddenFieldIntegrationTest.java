package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-050: a form with {@code th:action} and method POST gets its CSRF token from Spring
 * Security's request-data processor, with no field written in the template. This renders a real
 * page of the running application and counts the fields: one per form. Two would mean a template
 * still writes its own next to the automatic one; none would mean the automatic one stopped
 * working, which every POST form of the hosted UI depends on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class CsrfHiddenFieldIntegrationTest extends RedisBackedIntegrationTest {

  private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"");
  private static final Pattern POST_FORM = Pattern.compile("<form[^>]*method=\"post\"");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Test
  void theOperatorLoginPageHasExactlyOneCsrfFieldPerPostForm() throws Exception {
    String html = get("/platform/login");

    long forms = POST_FORM.matcher(html).results().count();
    long fields = CSRF_FIELD.matcher(html).results().count();

    assertThat(forms).as("POST forms on the page").isPositive();
    assertThat(fields).as("_csrf fields, one per POST form").isEqualTo(forms);
  }

  @Test
  void theOperatorRegistrationPageHasExactlyOneCsrfFieldPerPostForm() throws Exception {
    String html = get("/platform/register");

    long forms = POST_FORM.matcher(html).results().count();
    long fields = CSRF_FIELD.matcher(html).results().count();

    assertThat(forms).as("POST forms on the page").isPositive();
    assertThat(fields).as("_csrf fields, one per POST form").isEqualTo(forms);
  }

  private String get(final String path) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("GET " + path).isEqualTo(200);
    return response.body();
  }
}
