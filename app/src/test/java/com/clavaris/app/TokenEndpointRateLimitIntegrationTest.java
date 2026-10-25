package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-PERF-034. The tenant token endpoint used to allow 20 non-refresh requests per 5 minutes per
 * <em>claimed</em> {@code client_id}, counting every request, successful or not. Found by the
 * interactive-flow load test: with the defaults only 20 of 30 sign-ins for one client completed,
 * the other ten authenticating correctly and then being refused at the code exchange. The same rule
 * let anyone who knew a (public) {@code client_id} spend its allowance with 20 garbage requests and
 * lock that application out.
 *
 * <p>It is now keyed by client <em>and</em> source IP and its limit is a runaway backstop. These
 * tests pin both halves with a limit of 60: a client can go well past the old 20 from one address,
 * and an anonymous flood from another address does not touch it. The flood is sent with the {@code
 * X-Forwarded-For} the trusted proxy would set, the way production reaches the application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      "clavaris.rate-limit.token.tenant-per-client-ip-limit=60"
    })
class TokenEndpointRateLimitIntegrationTest extends RedisBackedIntegrationTest {

  private static final int LIMIT = 60;
  private static final String LEGITIMATE_BACKEND = "198.51.100.9";
  private static final String ATTACKER = "203.0.113.7";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private record Client(UUID organizationId, String clientId, String clientSecret) {}

  @Test
  void aClientCanCompleteFarMoreThanTwentyExchangesFromOneAddress() throws Exception {
    final Client client = registerClient();

    for (int attempt = 1; attempt <= 45; attempt++) {
      assertThat(tokenStatus(client, client.clientSecret(), LEGITIMATE_BACKEND))
          .as("exchange %d from one address, past the old cap of 20", attempt)
          .isEqualTo(200);
    }
  }

  @Test
  void anAnonymousFloodFromOneAddressDoesNotLockTheClientOutForEveryoneElse() throws Exception {
    final Client client = registerClient();
    int refused = 0;
    for (int attempt = 1; attempt <= LIMIT + 10; attempt++) {
      if (tokenStatus(client, "guess-" + attempt, ATTACKER) == 429) {
        refused++;
      }
    }
    assertThat(refused).as("the attacker's own address must hit the limit").isGreaterThan(0);

    assertThat(tokenStatus(client, client.clientSecret(), LEGITIMATE_BACKEND))
        .as("the real application, on its own address, is unaffected")
        .isEqualTo(200);
  }

  @Test
  void theLimitStillAppliesToTheAddressThatExceededIt() throws Exception {
    final Client client = registerClient();
    for (int attempt = 1; attempt <= LIMIT + 10; attempt++) {
      tokenStatus(client, "guess-" + attempt, ATTACKER);
    }

    assertThat(tokenStatus(client, client.clientSecret(), ATTACKER))
        .as("a flooding address stays refused for the rest of the window, even with a valid secret")
        .isEqualTo(429);
  }

  // ---- fixture ----

  private Client registerClient() throws IOException, InterruptedException {
    final String token = platformToken();
    final UUID organizationId = createOrganization(token);
    final String body =
        "{\"redirectUris\":[\"https://client.example.test/callback\"],"
            + "\"allowedGrantTypes\":[\"client_credentials\"],\"allowedScopes\":[\"openid\"],"
            + "\"requireConsent\":false}";
    final JsonNode client =
        objectMapper.readTree(
            post(token, "/api/v1/admin/organizations/" + organizationId + "/clients", body).body());
    return new Client(
        organizationId, client.get("clientId").asString(), client.get("clientSecret").asString());
  }

  private int tokenStatus(final Client client, final String secret, final String sourceAddress)
      throws IOException, InterruptedException {
    final String basic =
        Base64.getEncoder().encodeToString((client.clientId() + ":" + secret).getBytes());
    final HttpRequest request =
        HttpRequest.newBuilder(uri("/o/" + client.organizationId() + "/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Forwarded-For", sourceAddress)
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  private String platformToken() throws IOException, InterruptedException {
    final String basic =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    final HttpRequest request =
        HttpRequest.newBuilder(uri("/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    return objectMapper
        .readTree(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body())
        .get("access_token")
        .asString();
  }

  private UUID createOrganization(final String token) throws IOException, InterruptedException {
    final PlatformAccount owner =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    owner.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(owner);
    final String body =
        "{\"name\":\"Rate Co\",\"ownerPlatformAccountId\":\"" + owner.id().value() + "\"}";
    return UUID.fromString(
        objectMapper
            .readTree(post(token, "/api/v1/admin/organizations", body).body())
            .get("id")
            .asString());
  }

  private HttpResponse<String> post(final String token, final String path, final String json)
      throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
    return response;
  }

  private URI uri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
