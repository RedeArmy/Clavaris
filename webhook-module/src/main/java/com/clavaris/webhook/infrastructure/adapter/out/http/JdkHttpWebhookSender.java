package com.clavaris.webhook.infrastructure.adapter.out.http;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryOutcome;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookHttpSender;
import com.clavaris.webhook.infrastructure.adapter.out.security.SsrfCheckResult;
import com.clavaris.webhook.infrastructure.adapter.out.security.WebhookUrlSsrfChecker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link WebhookHttpSender} — plain {@code java.net.http.HttpClient}, same JDK-native
 * choice identity-module's own {@code ResendHttpClient} already makes for its own outbound HTTP
 * calls (no new dependency needed). Unlike {@code ResendHttpClient} (a fixed, trusted, known-good
 * endpoint), the target here is an arbitrary URL a tenant operator supplied — {@link
 * HttpClient.Redirect#NEVER} is deliberate: a redirect from a registered {@code https://} endpoint
 * could otherwise be used to smuggle a signed request to a URL this Organization never actually
 * registered.
 *
 * <p>TD-SEC-053: re-checks {@link WebhookUrlSsrfChecker} immediately before every real connection
 * attempt, not just once at registration ({@code RegisterWebhookEndpointService}'s own {@code
 * WebhookUrlSsrfGuard} call) — see that checker's own Javadoc for why DNS rebinding makes a
 * registration-time-only check insufficient. A blocked URL never reaches {@link #httpClient} at
 * all; it fails the same way a real network error would ({@link WebhookDeliveryOutcome} with {@code
 * delivered = false}), so it flows through the existing retry/outbox machinery unchanged rather
 * than needing a new failure path.
 */
// Two exits per catch clause below is clearer here than forcing a single-return shape onto three
// genuinely different outcomes (IOException, InterruptedException, a real response) — same
// rationale RegisterOAuthClientController's own identical suppression documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
@Component
class JdkHttpWebhookSender implements WebhookHttpSender {

  // Real consumer applications are expected to acknowledge quickly (Stripe's own guidance: respond
  // within a few seconds, do the real work asynchronously) — bounded so one slow/hanging endpoint
  // can't stall this dispatcher's whole delivery batch.
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final int FIRST_ERROR_STATUS = 300;

  private final HttpClient httpClient;
  private final WebhookUrlSsrfChecker ssrfChecker;

  /* package */ JdkHttpWebhookSender(
      @Value("${clavaris.webhook.delivery-connect-timeout-seconds:5}")
          final long connectTimeoutSeconds,
      final WebhookUrlSsrfChecker ssrfChecker) {
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    this.ssrfChecker = ssrfChecker;
  }

  @Override
  public WebhookDeliveryOutcome send(
      final String url, final Map<String, String> headers, final String body) {
    // TD-SEC-053: re-checked here, not only at registration — see this class's own Javadoc and
    // WebhookUrlSsrfChecker's own for why (DNS rebinding). No connection is ever attempted for a
    // URL that fails this check.
    final SsrfCheckResult ssrfCheck = ssrfChecker.check(url);
    if (!ssrfCheck.safe()) {
      return new WebhookDeliveryOutcome(
          false, null, "blocked by SSRF guard: " + ssrfCheck.reason());
    }

    final HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(requestBuilder::header);

    final HttpResponse<Void> response;
    try {
      response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
    } catch (final IOException e) {
      return new WebhookDeliveryOutcome(
          false, null, "network/IO failure: " + e.getClass().getSimpleName());
    } catch (final InterruptedException _) {
      // Standard JDK pattern: restore the interrupt flag before returning, same discipline as
      // ResendHttpClient's own identical catch.
      Thread.currentThread().interrupt();
      return new WebhookDeliveryOutcome(false, null, "interrupted");
    }

    final boolean success = response.statusCode() < FIRST_ERROR_STATUS;
    // BR-DATA-01: never the response body — only the status code, enough to distinguish
    // "delivered" from "the consumer's endpoint is down/misconfigured/rejecting", same discipline
    // ResendHttpClient's own identical error message already establishes.
    return new WebhookDeliveryOutcome(
        success, response.statusCode(), success ? null : "non-2xx status " + response.statusCode());
  }
}
