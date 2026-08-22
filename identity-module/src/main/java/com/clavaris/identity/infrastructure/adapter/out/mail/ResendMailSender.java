package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.OrganizationId;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements {@link MailSender} against Resend's HTTP API (plain {@link HttpClient}, not the Resend
 * SDK — a single POST endpoint doesn't justify an extra dependency, same reasoning already applied
 * to every other outbound HTTP integration in this codebase). The sending domain itself (whatever
 * {@code MAIL_FROM_ADDRESS} resolves to) is provisioned and DNS-verified with Resend outside this
 * codebase — nothing here assumes a specific registrar.
 *
 * <p>Builds the actual {@code {clavarisBaseUrl}/o/{organizationId}/...} link here, not in the
 * application layer — see {@link MailSender}'s own Javadoc for why that split exists. Also
 * implements {@link PlatformMailSender} (ADR-0012) — same HTTP mechanics, generic "Clavaris"
 * branding instead of a per-Organization one, {@code {clavarisBaseUrl}/platform/...} links instead
 * of {@code /o/{organizationId}/...}.
 */
@Component
class ResendMailSender implements MailSender, PlatformMailSender {

  @SuppressWarnings("PMD.LongVariable")
  private static final URI DEFAULT_RESEND_ENDPOINT = URI.create("https://api.resend.com/emails");

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  // Resend's API never redirects on a real request — treating 3xx as failure too, not just 4xx/5xx,
  // is deliberate: a redirect here means something is misconfigured (wrong host/path), not success.
  // Descriptive over PMD's default LongVariable threshold, same precedent as RefreshTokenSecret's
  // own RAW_VALUE_BYTE_LENGTH.
  @SuppressWarnings("PMD.LongVariable")
  private static final int FIRST_ERROR_STATUS = 300;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;
  private final String fromAddress;
  private final String baseUrl;
  private final URI resendEndpoint;

  // Package-private: constructed only by Spring's own component scan (via @Component above) —
  // MailSender (the port) is what every caller outside this package should depend on. @Autowired
  // is required now that a second constructor exists (below) — without it Spring has no way to
  // pick between two candidates.
  @Autowired
  /* package */ ResendMailSender(
      final ObjectMapper objectMapper,
      @Value("${clavaris.mail.resend-api-key}") final String apiKey,
      @Value("${clavaris.mail.from-address}") final String fromAddress,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String baseUrl) {
    this(
        HttpClient.newHttpClient(),
        objectMapper,
        apiKey,
        fromAddress,
        baseUrl,
        DEFAULT_RESEND_ENDPOINT);
  }

  // Test-only (TD-SEC-020): lets ResendMailSenderTest point this at a local stub HTTP server —
  // never the real Resend API — and inject a fully-controlled HttpClient to simulate IOException/
  // InterruptedException deterministically, without real network flakiness. Never invoked by
  // Spring itself; @Autowired on the constructor above resolves the ambiguity unambiguously.
  /* package */ ResendMailSender(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final String apiKey,
      final String fromAddress,
      final String baseUrl,
      final URI resendEndpoint) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.apiKey = apiKey;
    this.fromAddress = fromAddress;
    this.baseUrl = baseUrl;
    this.resendEndpoint = resendEndpoint;
  }

  @Override
  public void sendEmailVerification(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    final String link = link(organizationId, "verify-email", rawToken);
    send(
        toAddress,
        "Verify your email address",
        "<p>Confirm your email address to finish setting up your account:</p>"
            + htmlButton(link, "Verify email")
            + "<p>This link expires in 24 hours. If you didn't request this, you can ignore it.</p>");
  }

  @Override
  public void sendPasswordReset(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    final String link = link(organizationId, "reset-password", rawToken);
    send(
        toAddress,
        "Reset your password",
        "<p>A password reset was requested for this account:</p>"
            + htmlButton(link, "Reset password")
            + "<p>This link expires in 30 minutes and can only be used once. If you didn't request"
            + " this, you can safely ignore it — your password will not be changed.</p>");
  }

  @Override
  public void sendPlatformAccountEmailVerification(final String toAddress, final String rawToken) {
    final String link = platformLink("verify-email", rawToken);
    send(
        toAddress,
        "Verify your email address",
        "<p>Confirm your email address to finish setting up your Clavaris account:</p>"
            + htmlButton(link, "Verify email")
            + "<p>This link expires in 24 hours. If you didn't request this, you can ignore it.</p>");
  }

  @Override
  public void sendPlatformAccountPasswordReset(final String toAddress, final String rawToken) {
    final String link = platformLink("reset-password", rawToken);
    send(
        toAddress,
        "Reset your password",
        "<p>A password reset was requested for your Clavaris account:</p>"
            + htmlButton(link, "Reset password")
            + "<p>This link expires in 30 minutes and can only be used once. If you didn't request"
            + " this, you can safely ignore it — your password will not be changed.</p>");
  }

  // Extracted purely to remove the "<p><a href=\"" literal's duplication
  // (PMD.AvoidDuplicateLiterals)
  // — the four send*() methods above all render one clickable action link, just with a different
  // surrounding message.
  private String htmlButton(final String link, final String label) {
    return "<p><a href=\"" + link + "\">" + label + "</a></p>";
  }

  private String link(
      final OrganizationId organizationId, final String path, final String rawToken) {
    return baseUrl
        + "/o/"
        + organizationId.value()
        + "/"
        + path
        + "?token="
        + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
  }

  private String platformLink(final String path, final String rawToken) {
    return baseUrl
        + "/platform/"
        + path
        + "?token="
        + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
  }

  private void send(final String toAddress, final String subject, final String html) {
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
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (final IOException e) {
      throw new MailDeliveryException("Resend request failed (network/IO)", e);
    } catch (final InterruptedException e) {
      // Standard JDK pattern for a checked InterruptedException: restore the interrupt flag
      // before rethrowing as unchecked, so the interruption isn't silently swallowed for
      // whatever code is further up the call stack.
      Thread.currentThread().interrupt();
      throw new MailDeliveryException("Resend request interrupted", e);
    }

    if (response.statusCode() >= FIRST_ERROR_STATUS) {
      // BR-DATA-01: never log the request body itself (it carries the recipient's email address)
      // — only the status code, which is enough to distinguish "Resend is down/misconfigured"
      // from a real delivery success.
      throw new MailDeliveryException("Resend responded with status " + response.statusCode());
    }
  }
}
