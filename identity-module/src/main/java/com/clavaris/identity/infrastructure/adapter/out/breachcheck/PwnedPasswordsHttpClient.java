package com.clavaris.identity.infrastructure.adapter.out.breachcheck;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * BR-ID-07: the "speak HIBP's own Pwned Passwords range-query API" mechanics — same "how," not
 * "what," split {@code ResendHttpClient} already establishes for its own third-party HTTP
 * dependency. Returns the raw response lines (one {@code SUFFIX:COUNT} pair per matching hash
 * sharing the queried prefix) or throws; {@code PwnedPasswordsBreachedPasswordChecker} is what
 * decides what a failure means for the caller (BR-ID-07's own confirmed fail-open contract) — kept
 * out of this class the same way {@code ResendHttpClient} never decides whether a failed send
 * should propagate.
 *
 * <p>Wrapped in a {@link CircuitBreaker}, same "fail fast on a dependency already known to be down,
 * don't pay the full timeout again on every request" reasoning {@code ResendHttpClient}'s own
 * Javadoc documents — on the hot path of every registration/password-change, not a background job.
 */
// PMD.LongVariable: FIRST_ERROR_STATUS names exactly what it is — same precedent ResendHttpClient's
// own identical constant/suppression already documents.
@SuppressWarnings("PMD.LongVariable")
class PwnedPasswordsHttpClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final int FIRST_ERROR_STATUS = 300;

  private final HttpClient httpClient;
  private final URI rangeEndpoint;
  private final CircuitBreaker circuitBreaker;

  /* package */ PwnedPasswordsHttpClient(
      final HttpClient httpClient, final URI rangeEndpoint, final CircuitBreaker circuitBreaker) {
    this.httpClient = httpClient;
    this.rangeEndpoint = rangeEndpoint;
    this.circuitBreaker = circuitBreaker;
  }

  // PMD.AvoidCatchingGenericException: the trailing broad-Exception catch clause is defensive
  // only, matching Callable#call's own broad `throws Exception` signature executeCallable
  // propagates — same rationale ResendHttpClient's own identical catch block documents.
  // PMD.CyclomaticComplexity: each of the four catch clauses plus the status-code check is its own
  // genuinely distinct failure mode needing its own branch — same shape ResendHttpClient's own
  // identical suppression already documents for this exact catch chain.
  @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CyclomaticComplexity"})
  /* package */ List<String> lookupRange(final String prefix) {
    // Add-Padding: HIBP's own documented anti-traffic-analysis header — the response always
    // includes decoy entries so a network observer can't infer anything from the real response
    // size alone. A real privacy win for a security-sensitive IdP, one request header to add.
    final HttpRequest request =
        HttpRequest.newBuilder(rangeEndpoint.resolve("range/" + prefix))
            .timeout(REQUEST_TIMEOUT)
            .header("Add-Padding", "true")
            .GET()
            .build();

    final HttpResponse<String> response;
    try {
      response =
          circuitBreaker.executeCallable(
              () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    } catch (final CallNotPermittedException e) {
      throw new PwnedPasswordsLookupException(
          "Pwned Passwords circuit breaker is open — the API appears to be down", e);
    } catch (final IOException e) {
      throw new PwnedPasswordsLookupException("Pwned Passwords request failed (network/IO)", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PwnedPasswordsLookupException("Pwned Passwords request interrupted", e);
    } catch (final Exception e) {
      throw new PwnedPasswordsLookupException("Pwned Passwords request failed unexpectedly", e);
    }

    if (response.statusCode() >= FIRST_ERROR_STATUS) {
      throw new PwnedPasswordsLookupException(
          "Pwned Passwords responded with status " + response.statusCode());
    }

    return response.body().lines().toList();
  }
}
