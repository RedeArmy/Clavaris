package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-TEST-019 / BR-ADMIN-03: malformed input to the admin API is a 4xx, never a 5xx. The table of
 * requests is not written by hand: it is read from the OpenAPI document the application serves, so
 * a route added tomorrow is swept tomorrow with no one remembering to add it. For every {@code
 * POST}, {@code PUT} and {@code PATCH} under {@code /api/v1/admin} that takes a JSON body, each
 * field of the body in turn is sent a handful of values no client should ever send (empty, 5,000
 * characters, the wrong shape, a null, a null element, an unknown enum constant, a number out of
 * range) against real resources, and the one assertion is that the answer is not a server error.
 *
 * <p>It found {@code PUT .../profile} and {@code PUT .../social-login-policy} (TD-ARCH-060,
 * TD-ARCH-061), each one endpoint at a time and by a consumer's bug report before. Routes without a
 * body (the {@code :delete} family) are not touched: there is nothing to malform, and sending them
 * real ids would erase the resources the sweep needs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      // The sweep sends several hundred requests from one client in well under a minute; a 429
      // would
      // quietly shrink what it covers, so the limits are lifted here and the test asserts none was
      // answered.
      "clavaris.rate-limit.admin-api.per-client-limit=1000000",
      "clavaris.rate-limit.admin-api.accounts-delete.per-client-limit=1000000",
      "clavaris.rate-limit.admin-api.organizations-delete.per-client-limit=1000000",
      "clavaris.rate-limit.admin-api.workspace-members-write.per-client-limit=1000000",
      "clavaris.rate-limit.admin-api.accounts-impersonate.per-client-limit=1000000"
    })
// BR-ADMIN-03: malformed input to the admin API is a 4xx, never a 5xx.
class AdminApiHostileInputIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  // Known failures, each with the row that closes it. Empty: the two this sweep found are fixed.
  private static final List<String> KNOWN_FAILURES = List.of();

  // A floor, not an expectation of the exact number: a sweep that found nothing to sweep would pass
  // for the wrong reason.
  private static final int MINIMUM_ENDPOINTS_SWEPT = 20;

  private static final String BIG = "x".repeat(5000);

  @Value("${local.server.port}")
  private int port;

  @Autowired private RegisterAccountUseCase registerAccount;
  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private String token;

  @Test
  void noMalformedBodyToTheAdminApiEverProducesAServerError() throws Exception {
    token =
        PlatformApiTestSupport.platformAccessToken(
            http,
            objectMapper,
            uri("/oauth2/token"),
            String.join(" ", PlatformScopes.BOOTSTRAP_DEFAULT));
    final Resources resources = createResources();
    final JsonNode api = objectMapper.readTree(get("/v3/api-docs").body());

    final List<String> serverErrors = new ArrayList<>();
    final List<String> throttled = new ArrayList<>();
    int endpoints = 0;
    int requests = 0;
    for (final Map.Entry<String, JsonNode> path : api.get("paths").properties()) {
      if (!path.getKey().startsWith("/api/v1/admin/")) {
        continue;
      }
      for (final String method : List.of("post", "put", "patch")) {
        final JsonNode operation = path.getValue().get(method);
        final JsonNode schema = bodySchema(operation);
        if (schema == null) {
          continue;
        }
        final JsonNode properties = resolve(api, schema).get("properties");
        if (properties == null) {
          continue;
        }
        endpoints++;
        final String url = fill(path.getKey(), resources);
        for (final Map.Entry<String, JsonNode> field : properties.properties()) {
          for (final String value : hostileValues(api, field.getValue())) {
            final HttpResponse<String> response =
                send(method.toUpperCase(), url, "{\"" + field.getKey() + "\":" + value + "}");
            requests++;
            final String label =
                method.toUpperCase()
                    + " "
                    + path.getKey()
                    + " "
                    + field.getKey()
                    + "="
                    + abbreviate(value);
            if (response.statusCode() >= 500) {
              serverErrors.add(label + " => " + response.statusCode());
            } else if (response.statusCode() == 429) {
              throttled.add(label);
            }
          }
        }
      }
    }

    System.out.println(
        "hostile-input sweep: " + endpoints + " endpoints, " + requests + " requests");
    assertThat(throttled).as("requests answered 429, which would shrink the sweep").isEmpty();
    serverErrors.removeIf(error -> KNOWN_FAILURES.stream().anyMatch(error::startsWith));
    assertThat(serverErrors)
        .as("malformed input answered with a server error (BR-ADMIN-03)")
        .isEmpty();
    assertThat(endpoints)
        .as("endpoints with a JSON body found in the OpenAPI document")
        .isGreaterThanOrEqualTo(MINIMUM_ENDPOINTS_SWEPT);
  }

  // ---- what the OpenAPI document says about a request body

  private static JsonNode bodySchema(final JsonNode operation) {
    if (operation == null || operation.get("requestBody") == null) {
      return null;
    }
    final JsonNode content = operation.get("requestBody").get("content");
    if (content == null || content.get("application/json") == null) {
      return null;
    }
    return content.get("application/json").get("schema");
  }

  private static JsonNode resolve(final JsonNode api, final JsonNode schema) {
    final JsonNode ref = schema.get("$ref");
    if (ref == null) {
      return schema;
    }
    final String[] parts = ref.asString().substring(2).split("/");
    JsonNode node = api;
    for (final String part : parts) {
      node = node.get(part);
    }
    return node;
  }

  private static List<String> hostileValues(final JsonNode api, final JsonNode declared) {
    final JsonNode schema = resolve(api, declared);
    final List<String> values = new ArrayList<>(List.of("null"));
    if (schema.get("enum") != null) {
      values.addAll(List.of("\"BOGUS\"", "\"\"", "7"));
      return values;
    }
    final String type = schema.get("type") == null ? "object" : schema.get("type").asString();
    final String format = schema.get("format") == null ? "" : schema.get("format").asString();
    switch (type) {
      case "string" -> {
        if ("uuid".equals(format)) {
          values.addAll(List.of("\"not-a-uuid\"", "\"" + UUID.randomUUID() + "\""));
        } else {
          values.addAll(
              List.of(
                  "\"\"",
                  "\"" + BIG + "\"",
                  "\"!!bad\"",
                  "\"not a url\"",
                  "\"a@b\"",
                  "\"{\\\"a\\\":\"",
                  "\"http://x.test\"",
                  "\"https://example.com:99999/h\""));
        }
      }
      case "array" -> {
        values.addAll(List.of("[]", "[null]", "[\"\"]", "[\"" + BIG + "\"]", "[\"BOGUS\"]"));
        values.addAll(List.of("[\"not a url\"]", "[\"http://x.test\"]", "[7]", "[[]]"));
      }
      case "integer", "number" -> values.addAll(List.of("-1", "0", "2147483648", "\"x\""));
      case "boolean" -> values.addAll(List.of("\"yes\"", "7"));
      default -> values.addAll(List.of("\"x\"", "[]", "7"));
    }
    return values;
  }

  // ---- real resources, so a malformed body reaches the use case and not only the 404 in front

  private record Resources(UUID organization, UUID workspace, UUID account) {}

  private Resources createResources() throws Exception {
    final UUID owner = PlatformApiTestSupport.registerAPlatformAccount(platformAccounts);
    final UUID organization =
        UUID.fromString(
            objectMapper
                .readTree(
                    send(
                            "POST",
                            "/api/v1/admin/organizations",
                            "{\"name\":\"Sweep Co\",\"ownerPlatformAccountId\":\"" + owner + "\"}")
                        .body())
                .get("id")
                .asString());
    final UUID workspace =
        UUID.fromString(
            objectMapper
                .readTree(
                    send(
                            "POST",
                            "/api/v1/admin/organizations/" + organization + "/workspaces",
                            "{\"name\":\"Sweep Workspace\"}")
                        .body())
                .get("id")
                .asString());
    final UUID account =
        registerAccount
            .handle(
                new RegisterAccountCommand(
                    new OrganizationId(organization),
                    new Email("sweep@example.com"),
                    "a-correct-password",
                    null,
                    false))
            .accountId()
            .value();
    return new Resources(organization, workspace, account);
  }

  // The name in the template says what it is; a bare {id} is read from the segment before it.
  private static String fill(final String template, final Resources resources) {
    final TreeMap<Integer, String> replacements = new TreeMap<>();
    final java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(template);
    final StringBuilder out = new StringBuilder();
    int last = 0;
    while (matcher.find()) {
      out.append(template, last, matcher.start());
      final String name = matcher.group(1);
      final String before = template.substring(0, matcher.start());
      out.append(valueFor(name, before, resources));
      last = matcher.end();
    }
    out.append(template.substring(last));
    replacements.clear();
    return out.toString();
  }

  private static String valueFor(final String name, final String before, final Resources r) {
    final String segment = before.substring(before.lastIndexOf('/', before.length() - 2) + 1);
    return switch (name) {
      case "organizationId" -> r.organization().toString();
      case "workspaceId" -> r.workspace().toString();
      case "accountId" -> r.account().toString();
      case "provider" -> "GOOGLE";
      case "kid" -> "some-kid";
      case "id" ->
          segment.startsWith("accounts")
              ? r.account().toString()
              : segment.startsWith("organizations")
                  ? r.organization().toString()
                  : UUID.randomUUID().toString();
      default -> UUID.randomUUID().toString();
    };
  }

  // ---- HTTP

  private HttpResponse<String> get(final String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(final String method, final String path, final String body)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static String abbreviate(final String value) {
    return value.length() > 24 ? value.substring(0, 24) + "..." : value;
  }
}
