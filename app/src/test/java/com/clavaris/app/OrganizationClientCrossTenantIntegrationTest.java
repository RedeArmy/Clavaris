package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0023: end to end against real code, real Postgres, real HTTP — same "confirmed live, not
 * assumed" bar as {@code WorkspaceIntegrationTest}. Proves the one scenario this whole feature
 * exists to prevent: a real {@code OrganizationClient}'s own minted token, obtained via a real
 * {@code POST /oauth2/token} Basic-Auth exchange, reaching its own Organization's admin-API
 * resources but rejected against a different Organization's — not assumed from {@link
 * com.clavaris.app.infrastructure.config.OrganizationClientOwnershipFilterTest}'s own unit coverage
 * alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class OrganizationClientCrossTenantIntegrationTest extends RedisBackedIntegrationTest {

  private static final String FULL_SCOPE =
      "platform:organizations:write platform:secret-keys:write";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void anOrganizationClientsOwnTokenReachesItsOwnOrganizationButIsRejectedAgainstAnother()
      throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID ownOrganizationId = createOrganization(platformToken, "Secret Key Owner Co");
    UUID otherOrganizationId = createOrganization(platformToken, "Secret Key Victim Co");

    JsonNode secretKey =
        createSecretKey(platformToken, ownOrganizationId, "platform:rate-limit-policy:write");
    String secretKeyClientId = secretKey.get("clientId").asString();
    String secretKeyClientSecret = secretKey.get("clientSecret").asString();
    String secretKeyToken =
        requestClientToken(
            secretKeyClientId, secretKeyClientSecret, "platform:rate-limit-policy:write");

    HttpResponse<String> ownOrgResponse =
        setRateLimitPolicy(secretKeyToken, ownOrganizationId, 500);
    assertThat(ownOrgResponse.statusCode())
        .as("an OrganizationClient's own token must reach its own Organization's resources")
        .isEqualTo(200);

    HttpResponse<String> crossTenantResponse =
        setRateLimitPolicy(secretKeyToken, otherOrganizationId, 500);
    assertThat(crossTenantResponse.statusCode())
        .as("the exact cross-tenant attempt ADR-0023 exists to prevent")
        .isEqualTo(403);
  }

  @Test
  void aPlatformClientTokenIsUnaffectedByTheOwnershipFilter() throws Exception {
    String platformToken =
        requestPlatformAccessToken(FULL_SCOPE + " platform:rate-limit-policy:write");
    UUID organizationId = createOrganization(platformToken, "Platform Token Still Unscoped Co");

    HttpResponse<String> response = setRateLimitPolicy(platformToken, organizationId, 500);

    assertThat(response.statusCode())
        .as(
            "a PlatformClient token carries no organization_id claim — never affected by this filter")
        .isEqualTo(200);
  }

  @Test
  void theApiKeysEndpointReturnsAllExpectedFields() throws Exception {
    String platformToken = requestPlatformAccessToken(FULL_SCOPE);
    UUID organizationId = createOrganization(platformToken, "Api Keys Co");

    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/api-keys"))
            .header("Authorization", "Bearer " + platformToken)
            .GET()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = objectMapper.readTree(response.body());
    assertThat(body.get("publishableKey").asString()).startsWith("pk_test_");
    assertThat(body.get("frontendApiUrl").asString()).contains("/o/" + organizationId);
    assertThat(body.get("backendApiUrl").asString()).endsWith("/oauth2/token");
    assertThat(body.get("jwksUrl").asString())
        .contains("/o/" + organizationId)
        .endsWith("/oauth2/jwks");
    assertThat(body.get("jwksPublicKey").asString()).contains("BEGIN PUBLIC KEY");
    assertThat(body.get("configuredApiVersion").asString()).isEqualTo("v1");
    assertThat(body.get("latestApiVersion").asString()).isEqualTo("v1");
  }

  private JsonNode createSecretKey(String platformToken, UUID organizationId, String allowedScope)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/secret-keys"))
            .header("Authorization", "Bearer " + platformToken)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"allowedScopes\":[\"" + allowedScope + "\"]}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);
    return objectMapper.readTree(response.body());
  }

  private HttpResponse<String> setRateLimitPolicy(
      String bearerToken, UUID organizationId, int requestsPerMinute)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/organizations/" + organizationId + "/rate-limit-policy"))
            .header("Authorization", "Bearer " + bearerToken)
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofString(
                    "{\"requestsPerMinute\":" + requestsPerMinute + "}"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String requestClientToken(String clientId, String clientSecret, String scope)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private String requestPlatformAccessToken(String scope) throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganization(String platformToken, String name)
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
                        + registerAPlatformAccount()
                        + "\"}"))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  private UUID registerAPlatformAccount() {
    PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
