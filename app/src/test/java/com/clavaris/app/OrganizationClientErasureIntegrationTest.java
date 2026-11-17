package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

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
          + " platform:webhook-deliveries:replay platform:organizations:delete";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private RegisterAccountUseCase registerAccount;
  @Autowired private WebhookEndpointRepository webhookEndpoints;
  @Autowired private JdbcTemplate jdbcTemplate;

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
