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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * How a consuming application's client secret is stored and checked, through the real token
 * endpoint.
 *
 * <p>The server generates these secrets with 256 random bits, so they are stored with a fast hash
 * instead of Argon2id (see {@code HighEntropyClientSecret}); what that buys is a token endpoint
 * that no longer spends ~60 ms of CPU per call. What has to keep holding is everything around it:
 * the secret that was shown once still authenticates, any other does not, nothing stored contains
 * the secret, and a client registered before the scheme existed (an Argon2 hash in the table) keeps
 * working until its secret is rotated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret",
      "clavaris.rate-limit.token.per-client-limit=500"
    })
class ClientSecretHashingIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private record Registered(UUID organizationId, String clientId, String clientSecret) {}

  @Test
  void aRegisteredClientIsStoredWithTheFastHashAndNeverWithItsSecret() throws Exception {
    final Registered client = registerMachineClient();

    final String stored = storedHashOf(client.clientId());

    assertThat(stored).startsWith("{sha256}").doesNotContain(client.clientSecret());
    assertThat(client.clientSecret()).hasSizeGreaterThanOrEqualTo(32);
  }

  @Test
  void theSecretThatWasShownOnceAuthenticatesAndAnyOtherDoesNot() throws Exception {
    final Registered client = registerMachineClient();

    assertThat(tokenStatus(client, client.clientSecret())).isEqualTo(200);
    assertThat(tokenStatus(client, client.clientSecret() + "x")).isEqualTo(401);
    // An empty secret never reaches the hash check: the server answers invalid_request (400).
    assertThat(tokenStatus(client, "")).isIn(400, 401);
    assertThat(tokenStatus(client, "not-the-secret")).isEqualTo(401);
  }

  // A client registered before the scheme existed has an Argon2 hash in the table. It must keep
  // authenticating, with the same secret, until it is rotated.
  @Test
  void aClientRegisteredBeforeTheFastSchemeKeepsAuthenticatingThroughItsArgon2Hash()
      throws Exception {
    final Registered client = registerMachineClient();
    jdbcTemplate.update(
        "UPDATE oauth_clients SET client_secret_hash = ? WHERE client_id = ?",
        Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(client.clientSecret()),
        client.clientId());
    assertThat(storedHashOf(client.clientId())).startsWith("$argon2");

    assertThat(tokenStatus(client, client.clientSecret())).isEqualTo(200);
    assertThat(tokenStatus(client, client.clientSecret() + "x")).isEqualTo(401);
  }

  // The operator picks this one, so it cannot take the cheap path: it stays memory-hard.
  @Test
  void theOperatorChosenBootstrapSecretStaysOnArgon2() {
    final String stored =
        jdbcTemplate.queryForObject(
            "SELECT client_secret_hash FROM platform_clients WHERE client_id = ?",
            String.class,
            "test-platform-client");

    assertThat(stored).startsWith("$argon2");
  }

  // ---- fixture ----

  private Registered registerMachineClient() throws IOException, InterruptedException {
    final String token = platformToken();
    final UUID organizationId = createOrganization(token);
    final String body =
        "{\"redirectUris\":[\"https://client.example.test/callback\"],"
            + "\"allowedGrantTypes\":[\"client_credentials\"],\"allowedScopes\":[\"openid\"],"
            + "\"requireConsent\":false}";
    final JsonNode client =
        objectMapper.readTree(
            post(token, "/api/v1/admin/organizations/" + organizationId + "/clients", body).body());
    return new Registered(
        organizationId, client.get("clientId").asString(), client.get("clientSecret").asString());
  }

  private String storedHashOf(final String clientId) {
    return jdbcTemplate.queryForObject(
        "SELECT client_secret_hash FROM oauth_clients WHERE client_id = ?", String.class, clientId);
  }

  private int tokenStatus(final Registered client, final String secret)
      throws IOException, InterruptedException {
    final String basic =
        Base64.getEncoder().encodeToString((client.clientId() + ":" + secret).getBytes());
    final HttpRequest request =
        HttpRequest.newBuilder(uri("/o/" + client.organizationId() + "/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
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
        "{\"name\":\"Hash Co\",\"ownerPlatformAccountId\":\"" + owner.id().value() + "\"}";
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
