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

  // A display name longer than this is cut: mail clients truncate it anyway, and an Organization's
  // name has no length limit of its own that suits a header.
  private static final int MAX_SENDER_NAME = 64;

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

  /** Sends {@code email} from the configured sender, with no display name of its own. */
  /* package */ void send(final String toAddress, final Emails.Composed email) {
    send(toAddress, email, null);
  }

  // The From header: the configured address, with {@code senderName} as its display name when there
  // is one (an Organization's own emails are sent in its name). The name is user-supplied, so it is
  // reduced to plain printable text first: no quotes, angle brackets, backslashes or line breaks
  // that could end the display name early or add a header.
  private String sender(final String senderName) {
    final String name = displayName(senderName);
    return name.isEmpty() ? fromAddress : "\"" + name + "\" <" + addressOf(fromAddress) + ">";
  }

  /* package */ static String displayName(final String raw) {
    final StringBuilder name = new StringBuilder();
    for (final char character : raw == null ? new char[0] : raw.toCharArray()) {
      final boolean unsafe =
          Character.isISOControl(character)
              || character == '"'
              || character == '\\'
              || character == '<'
              || character == '>';
      name.append(unsafe ? ' ' : character);
    }
    final String collapsed = name.toString().strip().replaceAll("\\s+", " ");
    return collapsed.length() > MAX_SENDER_NAME
        ? collapsed.substring(0, MAX_SENDER_NAME).strip()
        : collapsed;
  }

  // "Name <a@b.c>" -> "a@b.c"; a bare address is returned as is.
  /* package */ static String addressOf(final String from) {
    final int open = from.lastIndexOf('<');
    final int close = from.lastIndexOf('>');
    return open >= 0 && close > open ? from.substring(open + 1, close).strip() : from.strip();
  }

  // PMD.CyclomaticComplexity: the circuit breaker added one more genuinely distinct failure mode
  // (CallNotPermittedException) on top of the pre-existing IOException/InterruptedException split
  // — same "each real outcome needs its own branch" shape AuthenticateWithPasswordService's own
  // identical suppression already documents. PMD.AvoidCatchingGenericException: the trailing
  // broad-Exception catch clause is defensive-only, matching Callable#call's own broad `throws
  // Exception` signature executeCallable propagates — see that catch block's own comment.
  @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.AvoidCatchingGenericException"})
  /* package */ void send(
      final String toAddress, final Emails.Composed email, final String senderName) {
    final Map<String, Object> requestBody =
        Map.of(
            "from", sender(senderName),
            "to", List.of(toAddress),
            "subject", email.subject(),
            "html", email.html(),
            "text", email.text());

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
}
