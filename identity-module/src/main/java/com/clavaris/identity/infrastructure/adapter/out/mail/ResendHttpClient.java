package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Code review finding (2026-09-01): the "speak Resend's HTTP API" mechanics — building the request,
 * sending it, translating a non-2xx/{@link IOException}/{@link InterruptedException} into a {@link
 * MailDeliveryException} — used to live inside {@code ResendMailSender} itself, alongside that
 * class's own knowledge of what a tenant-tier or platform-tier email actually says. Extracted so
 * this class only ever needs to know Resend's own API shape, and {@code ResendMailSender} only ever
 * needs to know Clavaris's own email content — same separation this codebase already applies
 * elsewhere between "how" and "what". No behavior change: same timeout, same status threshold, same
 * error messages, same BR-DATA-01 no-body-in-logs discipline.
 *
 * <p><b>SDE-III optimization pass, P2 point 4:</b> every real HTTP attempt now runs through a
 * {@link CircuitBreaker} — a real Resend outage today means every affected request still pays this
 * class's own {@link #REQUEST_TIMEOUT} (10s) before failing, on the single highest-traffic mail
 * path in the system (email verification, password reset, new-device alerts). Once the breaker
 * trips open, a request fails fast with {@link CallNotPermittedException} (translated to the same
 * {@link MailDeliveryException} every other failure mode here already produces — this class's own
 * callers already treat mail delivery as best-effort, see {@code RecordAccountLoginDeviceService}'s
 * own Javadoc) instead of waiting out the full timeout on a dependency already known to be down.
 */
final class ResendHttpClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  // Resend's API never redirects on a real request — treating 3xx as failure too, not just 4xx/5xx,
  // is deliberate: a redirect here means something is misconfigured (wrong host/path), not success.
  @SuppressWarnings("PMD.LongVariable")
  private static final int FIRST_ERROR_STATUS = 300;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final String fromAddress;
  private final URI resendEndpoint;
  private final CircuitBreaker circuitBreaker;

  /* package */ ResendHttpClient(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final String apiKey,
      final String fromAddress,
      final URI resendEndpoint,
      final CircuitBreaker circuitBreaker) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.apiKey = apiKey;
    this.fromAddress = fromAddress;
    this.resendEndpoint = resendEndpoint;
    this.circuitBreaker = circuitBreaker;
  }

  // PMD.CyclomaticComplexity: the circuit breaker added one more genuinely distinct failure mode
  // (CallNotPermittedException) on top of the pre-existing IOException/InterruptedException split
  // — same "each real outcome needs its own branch" shape AuthenticateWithPasswordService's own
  // identical suppression already documents. PMD.AvoidCatchingGenericException: the final
  // catch (Exception e) is defensive-only, matching Callable#call's own broad `throws Exception`
  // signature executeCallable propagates — see that catch block's own comment.
  @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.AvoidCatchingGenericException"})
  /* package */ void send(final String toAddress, final String subject, final String html) {
    final Map<String, Object> requestBody =
        Map.of("from", fromAddress, "to", List.of(toAddress), "subject", subject, "html", html);

    final String jsonBody;
    try {
      jsonBody = objectMapper.writeValueAsString(requestBody);
    } catch (final JacksonException e) {
      // Same "this is a programming error, fail loudly" stance as JpaEventOutboxWriter's own
      // equivalent catch — every field here is a plain String/List this system itself built.
      throw new MailDeliveryException("Failed to serialize Resend request body", e);
    }

    final HttpRequest request =
        HttpRequest.newBuilder(resendEndpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

    final HttpResponse<String> response;
    try {
      // SDE-III optimization pass, P2 point 4: same "fail fast on a dependency already known to
      // be down, don't pay the full timeout again" reasoning this class's own Javadoc documents.
      response =
          circuitBreaker.executeCallable(
              () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    } catch (final CallNotPermittedException e) {
      throw new MailDeliveryException(
          "Resend circuit breaker is open — Resend appears to be down", e);
    } catch (final IOException e) {
      throw new MailDeliveryException("Resend request failed (network/IO)", e);
    } catch (final InterruptedException e) {
      // Standard JDK pattern for a checked InterruptedException: restore the interrupt flag
      // before rethrowing as unchecked, so the interruption isn't silently swallowed for
      // whatever code is further up the call stack.
      Thread.currentThread().interrupt();
      throw new MailDeliveryException("Resend request interrupted", e);
    } catch (final Exception e) {
      // Unreachable in practice — the wrapped Callable only ever throws IOException/
      // InterruptedException itself; defensive only, matching Callable#call's own broad `throws
      // Exception` signature that executeCallable propagates.
      throw new MailDeliveryException("Resend request failed unexpectedly", e);
    }

    if (response.statusCode() >= FIRST_ERROR_STATUS) {
      // BR-DATA-01: never log the request body itself (it carries the recipient's email address)
      // — only the status code, which is enough to distinguish "Resend is down/misconfigured"
      // from a real delivery success.
      throw new MailDeliveryException("Resend responded with status " + response.statusCode());
    }
  }

  // Extracted purely to remove the "<p><a href=\"" literal's duplication
  // (PMD.AvoidDuplicateLiterals) across every send*() method that renders one clickable action
  // link. Static/stateless: this is plain HTML formatting, not a Resend-API concern, but lives here
  // rather than back on ResendMailSender since every method that calls it also calls send() on the
  // same line — one shared collaborator, not two.
  /* package */ static String htmlButton(final String link, final String label) {
    return "<p><a href=\"" + link + "\">" + label + "</a></p>";
  }

  // ADR-0024: the CODE-shaped counterpart to htmlButton above — every send*Code() method renders
  // one large, easy-to-retype value the same way. code itself is always this server's own
  // EmailOneTimeCode.generate() output (six ASCII digits), never attacker-controlled input, so no
  // HTML-escaping is needed here the way sendNewDeviceLoginNotification's own userAgent/sourceIp
  // interpolation requires.
  /* package */ static String htmlCode(final String code) {
    return "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px;\">" + code + "</p>";
  }
}
