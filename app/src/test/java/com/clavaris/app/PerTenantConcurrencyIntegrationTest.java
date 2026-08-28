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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
 * TD-TEST-001: stresses exactly the gap spike 0001 §6 named and this codebase inherited unevaluated
 * — {@code AuthorizationServerContextHolder}'s thread-local-based per-tenant context propagation
 * under real *concurrent* load, not the sequential single-request coverage every other issuer test
 * in this suite already has ({@link OrganizationOidcIssuerIntegrationTest} proves correctness, this
 * class proves it survives concurrency).
 *
 * <p>The one dynamic {@code SecurityFilterChain} (ADR-0010 §5, spike addendum) means every
 * Organization's request is served by literally the same chain instance, on whatever thread the
 * servlet container happens to hand it — {@code JWKSource}/{@code
 * OrganizationRegisteredClientRepository}/the issuer itself are all re-derived per-request from
 * {@code AuthorizationServerContextHolder}'s thread-local. A propagation bug here (context leaking
 * across threads, or a request seeing a *different* thread's already-set context) would surface as
 * one Organization's discovery document or JWKS occasionally, non-deterministically, describing a
 * *different* Organization — exactly the cross-tenant isolation failure ADR-0010 §5 calls
 * "structurally impossible, not policy-disallowed."
 *
 * <p>Fires many concurrent requests, interleaved across several Organizations, released together
 * via a {@link CountDownLatch} so they genuinely overlap in flight rather than merely running on a
 * thread pool one at a time — then asserts every single response's own issuer matches the
 * Organization it was actually requested for. Any single mismatch anywhere in the run fails the
 * test; this is not a statistical/percentile test, the invariant is absolute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "PLATFORM_BOOTSTRAP_CLIENT_ID=test-platform-client",
      "PLATFORM_BOOTSTRAP_CLIENT_SECRET=a-test-platform-secret"
    })
class PerTenantConcurrencyIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static final int ORGANIZATION_COUNT = 5;
  private static final int REQUESTS_PER_ORGANIZATION = 20;
  private static final int THREAD_POOL_SIZE = 16;

  @Value("${local.server.port}")
  private int port;

  @Autowired private PlatformAccountRepository platformAccounts;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void everyConcurrentDiscoveryRequestSeesOnlyItsOwnOrganizationsIssuer() throws Exception {
    final String platformToken = requestPlatformAccessToken();
    final Map<UUID, String> organizations = createOrganizations(platformToken, ORGANIZATION_COUNT);

    final List<UUID> requestPlan = buildInterleavedRequestPlan(organizations.keySet());
    final ConcurrentLinkedQueue<Mismatch> mismatches = new ConcurrentLinkedQueue<>();
    final ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    final CountDownLatch startGate = new CountDownLatch(1);
    final CountDownLatch doneLatch = new CountDownLatch(requestPlan.size());

    try {
      for (final UUID organizationId : requestPlan) {
        pool.submit(
            () -> {
              try {
                startGate.await();
                assertIssuerBelongsToItsOwnOrganization(organizationId, mismatches);
              } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      // Every task is queued and waiting on the same gate before any of them actually fires — this
      // is what makes the requests genuinely overlap in flight, not just "eventually all run."
      startGate.countDown();
      final boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
      assertThat(completed)
          .as("all %d concurrent requests completed in time", requestPlan.size())
          .isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(mismatches)
        .as(
            "every discovery response's own issuer must match the Organization it was requested"
                + " for — a mismatch here is a live cross-tenant context leak, not a flaky test")
        .isEmpty();
  }

  private void assertIssuerBelongsToItsOwnOrganization(
      final UUID organizationId, final ConcurrentLinkedQueue<Mismatch> mismatches) {
    try {
      final HttpResponse<String> response =
          get("/o/" + organizationId + "/.well-known/openid-configuration");
      if (response.statusCode() != 200) {
        mismatches.add(new Mismatch(organizationId, "http_status_" + response.statusCode()));
        return;
      }
      final JsonNode discovery = objectMapper.readTree(response.body());
      final String issuer = discovery.get("issuer").asString();
      final String expectedIssuerSuffix = "/o/" + organizationId;
      if (!issuer.endsWith(expectedIssuerSuffix)) {
        mismatches.add(new Mismatch(organizationId, issuer));
      }
    } catch (final IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      mismatches.add(new Mismatch(organizationId, "request_failed:" + e.getMessage()));
    }
  }

  private record Mismatch(UUID expectedOrganizationId, String actualIssuerOrFailure) {}

  // Round-robins organizations so consecutive submissions in program order still target different
  // Organizations — the interleaving itself is what maximizes the chance of two different
  // Organizations' requests genuinely executing on the same worker thread back-to-back, the exact
  // scenario a thread-local leak would surface in.
  private static List<UUID> buildInterleavedRequestPlan(final Set<UUID> organizationIds) {
    final List<UUID> ids = List.copyOf(organizationIds);
    final List<UUID> plan = new ArrayList<>(ids.size() * REQUESTS_PER_ORGANIZATION);
    for (int round = 0; round < REQUESTS_PER_ORGANIZATION; round++) {
      plan.addAll(ids);
    }
    return plan;
  }

  private Map<UUID, String> createOrganizations(final String platformToken, final int count)
      throws IOException, InterruptedException {
    final Map<UUID, String> organizations = new HashMap<>();
    for (final int i : IntStream.range(0, count).toArray()) {
      final UUID organizationId = createOrganization(platformToken, "Concurrency Co " + i);
      organizations.put(organizationId, "Concurrency Co " + i);
    }
    return organizations;
  }

  private String requestPlatformAccessToken() throws IOException, InterruptedException {
    final String basicAuth =
        Base64.getEncoder()
            .encodeToString("test-platform-client:a-test-platform-secret".getBytes());
    final HttpRequest request =
        HttpRequest.newBuilder(baseUri("/oauth2/token"))
            .header("Authorization", "Basic " + basicAuth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&scope=platform:organizations:write"))
            .build();
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body()).get("access_token").asString();
  }

  private UUID createOrganization(final String platformToken, final String name)
      throws IOException, InterruptedException {
    final HttpRequest request =
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
    final HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
  }

  // Same shortcut OrganizationOidcIssuerIntegrationTest's own identical helper already takes — a
  // real row written directly, not the full registration HTTP flow, since this suite never logs
  // in as this account.
  private UUID registerAPlatformAccount() {
    final PlatformAccount account =
        PlatformAccount.register(new Email("owner-" + UUID.randomUUID() + "@example.test"));
    account.attachPasswordCredential("not-a-real-hash-this-test-never-logs-in");
    platformAccounts.save(account);
    return account.id().value();
  }

  private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
    final HttpRequest request = HttpRequest.newBuilder(baseUri(path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI baseUri(final String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
