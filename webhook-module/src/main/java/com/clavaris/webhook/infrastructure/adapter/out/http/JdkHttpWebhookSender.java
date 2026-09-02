package com.clavaris.webhook.infrastructure.adapter.out.http;

import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookDeliveryOutcome;
import com.clavaris.webhook.application.usecase.deliverpendingwebhooks.WebhookHttpSender;
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

  /* package */ JdkHttpWebhookSender(
      @Value("${clavaris.webhook.delivery-connect-timeout-seconds:5}")
          final long connectTimeoutSeconds) {
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public WebhookDeliveryOutcome send(
      final String url, final Map<String, String> headers, final String body) {
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
