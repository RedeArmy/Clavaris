package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.ClientSecretHasher;
import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-SEC-018: the real, end-to-end proof that a {@code PlatformClient} can be rotated/revoked
 * through actual code, not raw SQL against production ({@code
 * incident-response-platform-client-compromise.md} §3a).
 *
 * <p>Each test registers its own dedicated {@code PlatformClient} row directly through {@link
 * PlatformClientRepository}, the same "write the fixture directly, not through the bootstrap
 * runner" pattern {@code SigningKeyRotationIntegrationTest}/{@code
 * OrganizationOidcIssuerIntegrationTest} already use for their own owner accounts — the shared
 * bootstrap client (used to authenticate the admin-API call in every test) is only ever the
 * *actor*, never the *target*, so no test's own rotation/revocation ever disturbs another test
 * sharing the same Postgres container within this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class PlatformClientRotationAndRevocationIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformClientRepository platformClients;
  @Autowired private ClientSecretHasher hasher;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rotatingATargetClientsSecretInvalidatesTheOldOneAndTheNewOneAuthenticates()
      throws Exception {
    String targetClientId = registerATargetPlatformClient("original-secret");
    String platformToken = requestBootstrapAccessToken("platform:platform-clients:rotate-secret");

    HttpResponse<String> rotateResponse = rotateSecret(platformToken, targetClientId);
    assertThat(rotateResponse.statusCode()).isEqualTo(200);
    String newSecret = objectMapper.readTree(rotateResponse.body()).get("clientSecret").asString();
    assertThat(newSecret).isNotEqualTo("original-secret");

    assertThat(clientCredentialsStatus(targetClientId, "original-secret"))
        .as("the old secret must no longer authenticate anything after rotation")
        .isEqualTo(401);
    assertThat(clientCredentialsStatus(targetClientId, newSecret))
        .as("the freshly rotated secret must authenticate immediately")
        .isEqualTo(200);
  }

  @Test
  void revokingATargetClientBlocksAnyFutureTokenRequestWithItsOwnCredentials() throws Exception {
    String targetClientId = registerATargetPlatformClient("target-secret");
    String platformToken = requestBootstrapAccessToken("platform:platform-clients:revoke");

    HttpResponse<String> revokeResponse = revoke(platformToken, targetClientId);
    assertThat(revokeResponse.statusCode()).isEqualTo(204);

    assertThat(clientCredentialsStatus(targetClientId, "target-secret"))
        .as("TD-SEC-018: a revoked PlatformClient must never mint a new token again")
        .isEqualTo(401);
  }

  @Test
  void returns404WhenRotatingAnUnknownClientId() throws Exception {
    String platformToken = requestBootstrapAccessToken("platform:platform-clients:rotate-secret");

    HttpResponse<String> response = rotateSecret(platformToken, "no-such-client");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void returns404WhenRevokingAnUnknownClientId() throws Exception {
    String platformToken = requestBootstrapAccessToken("platform:platform-clients:revoke");

    HttpResponse<String> response = revoke(platformToken, "no-such-client");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsRotationWithoutTheRotateSecretScope() throws Exception {
    String targetClientId = registerATargetPlatformClient("some-secret");
    String platformToken = requestBootstrapAccessToken("platform:organizations:write");

    HttpResponse<String> response = rotateSecret(platformToken, targetClientId);

    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  void rejectsRevocationWithoutTheRevokeScope() throws Exception {
    String targetClientId = registerATargetPlatformClient("some-secret");
    String platformToken = requestBootstrapAccessToken("platform:organizations:write");

    HttpResponse<String> response = revoke(platformToken, targetClientId);

    assertThat(response.statusCode()).isEqualTo(403);
  }

  // A real row written directly through the repository — deliberately never through the
  // bootstrap runner (which only ever creates the ONE fixed-clientId row from
  // PLATFORM_BOOTSTRAP_CLIENT_ID/SECRET) — so each test's own target client is fully isolated
  // from the shared bootstrap client every test authenticates the admin call with.
  private String registerATargetPlatformClient(String rawSecret) {
    String clientId = "target-" + UUID.randomUUID();
    PlatformClient client =
        PlatformClient.register(
            clientId, hasher.hash(rawSecret), List.of("platform:organizations:write"));
    platformClients.save(client);
    return clientId;
  }

  private HttpResponse<String> rotateSecret(String platformToken, String clientId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                baseUri("/api/v1/admin/platform-clients/" + clientId + "/rotate-secret"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> revoke(String platformToken, String clientId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/api/v1/admin/platform-clients/" + clientId + "/revoke"))
            .header("Authorization", "Bearer " + platformToken)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private int clientCredentialsStatus(String clientId, String clientSecret)
      throws IOException, InterruptedException {
    String basicAuth =
        Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
    HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  private String requestBootstrapAccessToken(String scope)
      throws IOException, InterruptedException {
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
    JsonNode body = objectMapper.readTree(response.body());
    JsonNode accessToken = body.get("access_token");
    if (accessToken == null) {
      throw new IllegalStateException(
          "Failed to obtain the bootstrap platform access token, response was: " + response.body());
    }
    return accessToken.asString();
  }

  private URI baseUri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
