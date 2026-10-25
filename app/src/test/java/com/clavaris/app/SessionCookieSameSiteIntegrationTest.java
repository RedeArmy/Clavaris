package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.app.support.TestMailSenderConfig;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccount;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real {@code Set-Cookie} a browser receives, on a connection the proxy reports as HTTPS.
 *
 * <p>{@code SameSite=None} is what lets an embedded iframe (ADR-0009) carry the session, and also
 * what removes {@code Lax}'s protection against cross-site requests. It is meant only for an
 * Organization that can actually be embedded; the unit tests pin the serializer's rules, and this
 * pins the whole chain they depend on: the {@code X-Forwarded-Proto} the proxy sends, the
 * Organization's environment, the query over its clients' verified domains, and the cache between
 * them. A development Organization is embeddable by design; a production one only once a client has
 * a verified domain with an embedding origin; the operator dashboard never is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMailSenderConfig.class)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class SessionCookieSameSiteIntegrationTest extends RedisBackedIntegrationTest {

  private static final String REDIRECT_URI = "https://client.example.test/callback";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void theOperatorDashboardSessionIsNeverCrossSite() throws Exception {
    final HttpResponse<String> response = getOverHttps("/platform/login");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(sessionCookie(response, "PLATFORM_SESSION"))
        .contains("SameSite=Lax")
        .contains("Secure")
        .doesNotContain("SameSite=None");
  }

  @Test
  void aDevelopmentOrganizationsSessionCanCrossSitesBecauseItCanBeEmbedded() throws Exception {
    final UUID developmentOrganization = createOrganization(platformToken());

    final HttpResponse<String> response = getOverHttps("/o/" + developmentOrganization + "/login");

    assertThat(sessionCookie(response, cookieNameOf(developmentOrganization)))
        .contains("SameSite=None")
        .contains("Secure");
  }

  @Test
  void aProductionOrganizationNobodyEmbedsKeepsTheLaxDefence() throws Exception {
    final String token = platformToken();
    final UUID productionOrganization = promoteToProduction(token, createOrganization(token));

    final HttpResponse<String> response = getOverHttps("/o/" + productionOrganization + "/login");

    assertThat(sessionCookie(response, cookieNameOf(productionOrganization)))
        .contains("SameSite=Lax")
        .contains("Secure")
        .doesNotContain("SameSite=None");
  }

  @Test
  void aProductionOrganizationBecomesCrossSiteOnceAClientHasAVerifiedEmbeddingDomain()
      throws Exception {
    final String token = platformToken();
    final UUID productionOrganization = promoteToProduction(token, createOrganization(token));
    final UUID oauthClientId = registerClient(token, productionOrganization);
    jdbcTemplate.update(
        """
        INSERT INTO client_domain_configs
          (id, oauth_client_id, mode, hostname, verification_status, dns_txt_challenge_token,
           embedding_origin, verified_at, created_at, updated_at)
        VALUES (?, ?, 'CNAME', ?, 'VERIFIED', 'challenge-token', 'https://app.example.test', ?, ?, ?)
        """,
        UUID.randomUUID(),
        oauthClientId,
        "login-" + UUID.randomUUID() + ".example.test",
        java.sql.Timestamp.from(Instant.now()),
        java.sql.Timestamp.from(Instant.now()),
        java.sql.Timestamp.from(Instant.now()));

    final HttpResponse<String> response = getOverHttps("/o/" + productionOrganization + "/login");

    assertThat(sessionCookie(response, cookieNameOf(productionOrganization)))
        .contains("SameSite=None")
        .contains("Secure");
  }

  @Test
  void overPlainHttpNoOrganizationEverGetsSameSiteNone() throws Exception {
    final UUID developmentOrganization = createOrganization(platformToken());

    final HttpResponse<String> response =
        httpClient.send(
            HttpRequest.newBuilder(uri("/o/" + developmentOrganization + "/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(sessionCookie(response, cookieNameOf(developmentOrganization)))
        .contains("SameSite=Lax")
        .doesNotContain("Secure");
  }

  // ---- helpers ----

  private static String cookieNameOf(final UUID organizationId) {
    return "SESSION_" + organizationId.toString().replace("-", "");
  }

  private static String sessionCookie(final HttpResponse<?> response, final String name) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(header -> header.startsWith(name + "="))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no " + name + " cookie in " + response.headers().allValues("Set-Cookie")));
  }

  // What Caddy sends for every request it proxies; forward-headers-strategy=framework turns it into
  // request.isSecure(), which is what the cookie serializer reads.
  private HttpResponse<String> getOverHttps(final String path)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(uri(path)).header("X-Forwarded-Proto", "https").GET().build(),
        HttpResponse.BodyHandlers.ofString());
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
                    "grant_type=client_credentials&scope="
                        + URLEncoder.encode(
                            "platform:organizations:write", StandardCharsets.UTF_8)))
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
        "{\"name\":\"Cookie Co\",\"ownerPlatformAccountId\":\"" + owner.id().value() + "\"}";
    return UUID.fromString(
        objectMapper
            .readTree(post(token, "/api/v1/admin/organizations", body).body())
            .get("id")
            .asString());
  }

  private UUID promoteToProduction(final String token, final UUID developmentOrganization)
      throws IOException, InterruptedException {
    final HttpResponse<String> response =
        post(
            token,
            "/api/v1/admin/organizations/"
                + developmentOrganization
                + ":create-production-environment",
            "{\"name\":\"Cookie Co (production)\"}");
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private UUID registerClient(final String token, final UUID organizationId)
      throws IOException, InterruptedException {
    final String body =
        "{\"redirectUris\":[\""
            + REDIRECT_URI
            + "\"],\"allowedGrantTypes\":[\"authorization_code\"],"
            + "\"allowedScopes\":[\"openid\"],\"requireConsent\":false}";
    final JsonNode client =
        objectMapper.readTree(
            post(token, "/api/v1/admin/organizations/" + organizationId + "/clients", body).body());
    return UUID.fromString(client.get("id").asString());
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
