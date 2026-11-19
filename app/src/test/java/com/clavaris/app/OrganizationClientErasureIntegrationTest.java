package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.clavaris.app.infrastructure.adapter.out.bridge.PendingSessionClosureJob;
import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.webhook.application.usecase.registerwebhookendpoint.WebhookEndpointRepository;
import com.clavaris.webhook.domain.model.WebhookEndpoint;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-SEC-101, end to end against real code, real Postgres and real HTTP: a consumer holding only
 * its own Organization's Secret Key can delete and suspend its own accounts and run its own webhook
 * endpoints, and cannot touch another Organization's. Found by the JobSeeker integration spike,
 * where the only credential able to delete a user was the platform-wide one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
// BR-PLATFORM-06: an OrganizationClient is confined to its allowlist and its own Organization.
// BR-DATA-03: deleting an account removes the record; here it is the consumer's own call.
class OrganizationClientErasureIntegrationTest extends RedisBackedIntegrationTest {

  private static final String SCOPES =
      "platform:accounts:delete platform:accounts:suspend platform:webhook-endpoints:write"
          + " platform:webhook-deliveries:replay platform:organizations:delete"
          + " platform:accounts:read-status";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private RegisterAccountUseCase registerAccount;
  @Autowired private WebhookEndpointRepository webhookEndpoints;
  @Autowired private JdbcTemplate jdbcTemplate;
  // Run by hand: RedisBackedIntegrationTest keeps the scheduled run from ever firing in a test.
  @Autowired private PendingSessionClosureJob pendingSessionClosureJob;

  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void aSecretKeySuspendsReactivatesAndDeletesItsOwnAccountsButNeverAnotherOrganizations()
      throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Erasure Owner Co");
    UUID other = createOrganization(platformToken, "Erasure Foreign Co");
    String token = secretKeyToken(platformToken, own);
    UUID suspended = account(own, "suspend-me@example.com");
    UUID deleted = account(own, "delete-me@example.com");
    UUID foreign = account(other, "not-yours@example.com");

    assertThat(post(token, "/api/v1/admin/accounts/" + suspended + ":suspend").statusCode())
        .as("suspend an own account")
        .isBetween(200, 204);
    assertThat(statusOf(suspended)).isEqualTo("SUSPENDED");
    assertThat(post(token, "/api/v1/admin/accounts/" + suspended + ":reactivate").statusCode())
        .as("reactivate it again")
        .isBetween(200, 204);
    assertThat(statusOf(suspended)).isEqualTo("ACTIVE");

    assertThat(post(token, "/api/v1/admin/accounts/" + deleted + ":delete").statusCode())
        .as("delete an own account")
        .isEqualTo(204);
    assertThat(accountExists(deleted)).isFalse();

    assertThat(post(token, "/api/v1/admin/accounts/" + foreign + ":suspend").statusCode())
        .as("another Organization's account: suspend")
        .isEqualTo(403);
    assertThat(post(token, "/api/v1/admin/accounts/" + foreign + ":delete").statusCode())
        .as("another Organization's account: delete")
        .isEqualTo(403);
    assertThat(accountExists(foreign)).as("the foreign account is untouched").isTrue();
    assertThat(statusOf(foreign)).isEqualTo("ACTIVE");
    assertThat(post(token, "/api/v1/admin/accounts/" + UUID.randomUUID() + ":delete").statusCode())
        .as("an account that does not exist")
        .isEqualTo(403);
  }

  @Test
  void aSecretKeyRunsItsOwnWebhookEndpointsButNeverAnotherOrganizations() throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Webhook Owner Co");
    UUID other = createOrganization(platformToken, "Webhook Foreign Co");
    String token = secretKeyToken(platformToken, own);
    WebhookEndpoint mine = endpointOf(own);
    WebhookEndpoint theirs = endpointOf(other);
    webhookEndpoints.insert(mine);
    webhookEndpoints.insert(theirs);
    String base = "/api/v1/admin/webhook-endpoints/";

    assertThat(post(token, base + mine.id() + ":deactivate").statusCode()).isBetween(200, 204);
    assertThat(endpointActive(mine.id())).isFalse();
    assertThat(post(token, base + mine.id() + ":activate").statusCode()).isBetween(200, 204);
    assertThat(endpointActive(mine.id())).isTrue();
    assertThat(post(token, base + mine.id() + ":rotate-secret").statusCode()).isEqualTo(200);
    assertThat(get(token, base + mine.id() + "/deliveries").statusCode()).isEqualTo(200);

    assertThat(post(token, base + theirs.id() + ":deactivate").statusCode()).isEqualTo(403);
    assertThat(post(token, base + theirs.id() + ":rotate-secret").statusCode()).isEqualTo(403);
    assertThat(get(token, base + theirs.id() + "/deliveries").statusCode()).isEqualTo(403);
    assertThat(
            post(token, base + theirs.id() + "/deliveries/" + UUID.randomUUID() + ":replay")
                .statusCode())
        .isEqualTo(403);
    assertThat(endpointActive(theirs.id())).as("the foreign endpoint is untouched").isTrue();
  }

  // TD-FUT-081 / ADR-0047: a consumer asks whether an Account is still allowed without spending a
  // refresh token, for its own Organization's Accounts and no one else's.
  @Test
  void aSecretKeyReadsTheStatusOfItsOwnAccountsInEveryStateAndNothingElse() throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Status Read Co");
    String token = secretKeyToken(platformToken, own);
    UUID active = account(own, "status-active@example.com");
    UUID suspended = account(own, "status-suspended@example.com");
    UUID banned = account(own, "status-banned@example.com");
    UUID pending = account(own, "status-pending@example.com");
    UUID rejected = account(own, "status-rejected@example.com");
    assertThat(post(token, "/api/v1/admin/accounts/" + suspended + ":suspend").statusCode())
        .isEqualTo(204);
    setStatus(banned, "BANNED");
    setStatus(pending, "PENDING_APPROVAL");
    setStatus(rejected, "REJECTED");

    assertThat(statusRead(token, active)).isEqualTo("ACTIVE");
    assertThat(statusRead(token, suspended)).isEqualTo("SUSPENDED");
    assertThat(statusRead(token, banned)).isEqualTo("BANNED");
    assertThat(statusRead(token, pending)).isEqualTo("PENDING_APPROVAL");
    assertThat(statusRead(token, rejected)).isEqualTo("REJECTED");

    HttpResponse<String> response = get(token, "/api/v1/admin/accounts/" + active + "/status");
    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.propertyNames()).as("no personal data, two fields").hasSize(2);
    assertThat(response.body()).doesNotContain("example.com");

    assertThat(post(token, "/api/v1/admin/accounts/" + suspended + ":reactivate").statusCode())
        .isEqualTo(204);
    assertThat(statusRead(token, suspended)).as("a change is seen at once").isEqualTo("ACTIVE");
  }

  // One answer for every account that is not this Organization's, so the read tells nothing about
  // which ids exist elsewhere or ever existed (ADR-0044).
  @Test
  void anotherOrganizationsUnknownAndErasedAccountsAreTheSame403() throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Status Oracle Own Co");
    UUID other = createOrganization(platformToken, "Status Oracle Other Co");
    String token = secretKeyToken(platformToken, own);
    UUID foreign = account(other, "status-foreign@example.com");
    UUID erased = account(own, "status-erased@example.com");
    assertThat(post(token, "/api/v1/admin/accounts/" + erased + ":delete").statusCode())
        .isEqualTo(204);

    assertThat(get(token, "/api/v1/admin/accounts/" + foreign + "/status").statusCode())
        .as("another Organization's")
        .isEqualTo(403);
    assertThat(get(token, "/api/v1/admin/accounts/" + UUID.randomUUID() + "/status").statusCode())
        .as("unknown")
        .isEqualTo(403);
    assertThat(get(token, "/api/v1/admin/accounts/" + erased + "/status").statusCode())
        .as("erased")
        .isEqualTo(403);
    assertThat(get(token, "/api/v1/admin/accounts/not-a-uuid/status").statusCode())
        .as("not an id")
        .isEqualTo(403);
  }

  @Test
  void aSecretKeyWithoutTheStatusScopeCannotReadEvenItsOwnAccount() throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Status Scope Co");
    JsonNode key = createSecretKey(platformToken, own, List.of("platform:accounts:suspend"));
    String token =
        clientToken(
            key.get("clientId").asString(),
            key.get("clientSecret").asString(),
            "platform:accounts:suspend");
    UUID mine = account(own, "status-scope@example.com");

    assertThat(get(token, "/api/v1/admin/accounts/" + mine + "/status").statusCode())
        .isEqualTo(403);
  }

  // A platform credential carries no Organization, so an unknown id reaches the controller and is
  // an ordinary 404.
  @Test
  void aPlatformClientWithTheScopeGetsA404ForAnUnknownAccount() throws Exception {
    String token =
        PlatformApiTestSupport.platformAccessToken(
            httpClient, objectMapper, baseUri("/oauth2/token"), "platform:accounts:read-status");

    assertThat(get(token, "/api/v1/admin/accounts/" + UUID.randomUUID() + "/status").statusCode())
        .isEqualTo(404);
  }

  private String statusRead(final String token, final UUID accountId) throws Exception {
    HttpResponse<String> response = get(token, "/api/v1/admin/accounts/" + accountId + "/status");
    assertThat(response.statusCode()).isEqualTo(200);
    return objectMapper.readTree(response.body()).get("status").asString();
  }

  private void setStatus(final UUID accountId, final String status) {
    jdbcTemplate.update("update accounts set status = ? where id = ?", status, accountId);
  }

  // TD-ARCH-058/059: bad input is a 400 and creates nothing. It was a 500 for a URL that is not
  // https, and an impossible port was accepted.
  @Test
  void aWebhookUrlThatCanNeverWorkIsA400AndRegistersNothing() throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Webhook Url Co");
    String token = secretKeyToken(platformToken, own);
    String path = "/api/v1/admin/organizations/" + own + "/webhook-endpoints";

    for (String url :
        new String[] {
          "http://example.com/h",
          "ftp://example.com/h",
          "javascript:alert(1)",
          "not a url",
          "https://example.com:0/h",
          "https://example.com:65536/h",
          "https://example.com:99999/h"
        }) {
      HttpResponse<String> response =
          postJson(token, path, "{\"url\":\"" + url + "\",\"subscribedEventTypes\":[\"x\"]}");
      assertThat(response.statusCode()).as(url).isEqualTo(400);
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from webhook_endpoints where organization_id = ?",
                Integer.class,
                own))
        .as("nothing was registered")
        .isZero();
  }

  private HttpResponse<String> postJson(final String token, final String path, final String body)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(baseUri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // The allowlist stays closed for what was not opened: deleting an Organization is still the
  // operator's alone, even for a Secret Key holding the scope and acting on its own Organization.
  @Test
  void deletingTheOrganizationItselfStaysForbiddenToASecretKey() throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Not Deletable Co");
    String token = secretKeyToken(platformToken, own);

    assertThat(post(token, "/api/v1/admin/organizations/" + own + ":delete").statusCode())
        .isEqualTo(403);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from organizations where id = ?", Integer.class, own))
        .isEqualTo(1);
  }

  // The scope still gates it: a Secret Key minted without accounts:delete cannot delete even its
  // own account, whatever the ownership filter allows.
  @Test
  void aSecretKeyWithoutTheScopeStillCannotDeleteItsOwnAccount() throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Scope Still Needed Co");
    JsonNode key = createSecretKey(platformToken, own, List.of("platform:accounts:suspend"));
    String token =
        clientToken(
            key.get("clientId").asString(),
            key.get("clientSecret").asString(),
            "platform:accounts:suspend");
    UUID mine = account(own, "scope-needed@example.com");

    assertThat(post(token, "/api/v1/admin/accounts/" + mine + ":delete").statusCode())
        .isEqualTo(403);
    assertThat(accountExists(mine)).isTrue();
  }

  // TD-ARCH-057: with Redis unreachable a suspension, a reactivation and a deletion still happen
  // (the state is in Postgres), the sessions still to be closed are recorded, and they are closed
  // once Redis answers. They used to fail with a 500 after a command timeout.
  @Test
  void accountLifecycleStillWorksWhileRedisIsDownAndTheSessionsAreClosedAfterwards()
      throws Exception {
    String platformToken = platformToken();
    UUID own = createOrganization(platformToken, "Redis Outage Co");
    String token = secretKeyToken(platformToken, own);
    UUID suspended = account(own, "outage-suspend@example.com");
    UUID deleted = account(own, "outage-delete@example.com");

    pauseRedis();
    try {
      assertThat(post(token, "/api/v1/admin/accounts/" + suspended + ":suspend").statusCode())
          .as("suspend while Redis is down")
          .isEqualTo(204);
      assertThat(statusOf(suspended)).isEqualTo("SUSPENDED");
      assertThat(post(token, "/api/v1/admin/accounts/" + deleted + ":delete").statusCode())
          .as("delete while Redis is down")
          .isEqualTo(204);
      assertThat(accountExists(deleted)).isFalse();
      assertThat(post(token, "/api/v1/admin/accounts/" + suspended + ":reactivate").statusCode())
          .as("reactivate while Redis is down")
          .isEqualTo(204);
      assertThat(statusOf(suspended)).isEqualTo("ACTIVE");
      assertThat(pendingClosures(suspended, deleted))
          .as("both accounts wait for their sessions to be closed")
          .isEqualTo(2);
    } finally {
      unpauseRedis();
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              pendingSessionClosureJob.closePendingSessions();
              assertThat(pendingClosures(suspended, deleted))
                  .as("once Redis answers the job closes the sessions and forgets the accounts")
                  .isZero();
            });
  }

  private int pendingClosures(final UUID first, final UUID second) {
    return jdbcTemplate.queryForObject(
        "select count(*) from pending_session_closures where account_id in (?, ?)",
        Integer.class,
        first,
        second);
  }

  private WebhookEndpoint endpointOf(final UUID organizationId) {
    return WebhookEndpoint.register(
        organizationId, "https://consumer.example.com/hook", null, List.of("x"), "secret");
  }

  private UUID account(final UUID organizationId, final String email) {
    return registerAccount
        .handle(
            new RegisterAccountCommand(
                new OrganizationId(organizationId),
                new Email(email),
                "a-correct-password",
                null,
                false))
        .accountId()
        .value();
  }

  private String statusOf(final UUID accountId) {
    return jdbcTemplate.queryForObject(
        "select status from accounts where id = ?", String.class, accountId);
  }

  private boolean accountExists(final UUID accountId) {
    return jdbcTemplate.queryForObject(
            "select count(*) from accounts where id = ?", Integer.class, accountId)
        > 0;
  }

  private boolean endpointActive(final UUID endpointId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "select active from webhook_endpoints where id = ?", Boolean.class, endpointId));
  }

  private String secretKeyToken(final String platformToken, final UUID organizationId)
      throws IOException, InterruptedException {
    JsonNode key = createSecretKey(platformToken, organizationId, List.of(SCOPES.split(" ")));
    return clientToken(key.get("clientId").asString(), key.get("clientSecret").asString(), SCOPES);
  }

  private JsonNode createSecretKey(
      final String platformToken, final UUID organizationId, final List<String> scopes)
      throws IOException, InterruptedException {
    String quoted = String.join("\",\"", scopes);
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/secret-keys"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"allowedScopes\":[\"" + quoted + "\"]}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("minting the Secret Key").isEqualTo(201);
    return objectMapper.readTree(response.body());
  }

  private String clientToken(final String clientId, final String clientSecret, final String scope)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=" + scope.replace(" ", "%20")))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private HttpResponse<String> post(final String token, final String path)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(baseUri(path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(final String token, final String path)
      throws IOException, InterruptedException {
    return httpClient.send(
        HttpRequest.newBuilder(baseUri(path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String platformToken() throws IOException, InterruptedException {
    return PlatformApiTestSupport.platformAccessToken(
        httpClient,
        objectMapper,
        baseUri("/oauth2/token"),
        "platform:organizations:write platform:secret-keys:write");
  }

  private UUID createOrganization(final String platformToken, final String name)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/organizations"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"name\":\""
                        + name
                        + "\",\"ownerPlatformAccountId\":\""
                        + PlatformApiTestSupport.registerAPlatformAccount(platformAccounts)
                        + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
