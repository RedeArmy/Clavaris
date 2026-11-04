package com.clavaris.app.support;

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
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * The two steps nearly every platform-API integration test starts with: a {@code
 * client_credentials} token for the platform issuer, and a platform account to own what the test
 * creates. TD-TEST-012: both used to be copied into 21 test classes, each copy free to drift (one
 * had already lost its scope encoding); each class now keeps only its own scope and credentials.
 */
public final class PlatformApiTestSupport {

  /** The bootstrap client every platform-API test seeds, unless it needs its own (rate limits). */
  public static final String DEFAULT_CLIENT_ID = "test-platform-client";

  /** The secret seeded for {@link #DEFAULT_CLIENT_ID}. */
  public static final String DEFAULT_CLIENT_SECRET = "a-test-platform-secret";

  private PlatformApiTestSupport() {}

  /** A platform access token for the default test client, with the given space-separated scopes. */
  public static String platformAccessToken(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final URI tokenEndpoint,
      final String scope)
      throws IOException, InterruptedException {
    return platformAccessToken(
        httpClient, objectMapper, tokenEndpoint, DEFAULT_CLIENT_ID, DEFAULT_CLIENT_SECRET, scope);
  }

  /** The same for a test that seeds its own client. */
  public static String platformAccessToken(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final URI tokenEndpoint,
      final String clientId,
      final String clientSecret,
      final String scope)
      throws IOException, InterruptedException {
    final String basicAuth =
        Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    // Encoded: a raw space between two scopes is not valid form encoding and SAS then grants only
    // the first, which surfaced elsewhere as a confusing 403 two calls later.
    final HttpRequest request =
        HttpRequest.newBuilder(tokenEndpoint)
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope="
                        + URLEncoder.encode(scope, StandardCharsets.UTF_8)))
            .build();
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("platform token request failed: " + response.body());
    }
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  /** Persists a platform account with a placeholder credential and returns its id. */
  public static UUID registerAPlatformAccount(final PlatformAccountRepository platformAccounts) {
    final PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    // The repository rejects an account with no credential; this one never signs in.
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }
}
