package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.resilience.CircuitBreakerMetricsBinder;
import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Implements {@link MailSender} against Resend's HTTP API — the actual "speak Resend's API"
 * mechanics (request building, error translation) live in {@link ResendHttpClient} (code review
 * finding, 2026-09-01: extracted so this class only ever needs to know what a Clavaris email says,
 * never how the HTTP call to Resend itself works). The sending domain itself (whatever {@code
 * MAIL_FROM_ADDRESS} resolves to) is provisioned and DNS-verified with Resend outside this codebase
 * — nothing here assumes a specific registrar.
 *
 * <p>Builds the actual {@code {clavarisBaseUrl}/o/{organizationId}/...} link here, not in the
 * application layer — see {@link MailSender}'s own Javadoc for why that split exists. Also
 * implements {@link PlatformMailSender} (ADR-0012) — same HTTP mechanics, generic "Clavaris"
 * branding instead of a per-Organization one, {@code {clavarisBaseUrl}/platform/...} links instead
 * of {@code /o/{organizationId}/...}.
 */
// Implements both MailSender (now 7 send* methods, ADR-0024 added 4 more passwordless/verification-
// code ones) and PlatformMailSender (3 more) in one class — deliberately, same "one class, same
// HTTP mechanics, two ports" design this class's own Javadoc already explains.
// PMD.LongVariable: the repeated string is "PMD.LongVariable" itself, used on several
// descriptively-named fields/parameters (DEFAULT_RESEND_ENDPOINT, failureRateThreshold,
// waitDurationInOpenStateSeconds) — same rationale as identity-module's own IdentityUseCaseConfig
// class-level suppression for this exact PMD-annotation-string-as-literal false positive.
@SuppressWarnings({"PMD.TooManyMethods", "PMD.LongVariable"})
@Component
class ResendMailSender implements MailSender, PlatformMailSender {

  private static final URI DEFAULT_RESEND_ENDPOINT = URI.create("https://api.resend.com/emails");

  private final ResendHttpClient httpClient;
  private final String baseUrl;

  // Package-private: constructed only by Spring's own component scan (via @Component above) —
  // MailSender (the port) is what every caller outside this package should depend on. @Autowired
  // is required now that other constructors exist (below) — without it Spring has no way to pick
  // among the candidates.
  //
  // SDE-III optimization pass, P2 point 4: the three new @Value params tune this dependency's own
  // CircuitBreaker (see ResendHttpClient's own Javadoc for why it needs one at all) — defaults
  // chosen conservatively (a real Resend outage should trip this well before every request queues
  // up behind a 10s timeout, but not so eagerly that a handful of genuinely transient failures
  // trips it): 50% of the last 10 calls failing opens the circuit, then 30s before the next probe.
  // java:S107: one parameter per collaborating value — same rationale as every other
  // multi-collaborator constructor in this codebase.
  @SuppressWarnings("java:S107")
  @Autowired
  /* package */ ResendMailSender(
      final ObjectMapper objectMapper,
      @Value("${clavaris.mail.resend-api-key}") final String apiKey,
      @Value("${clavaris.mail.from-address}") final String fromAddress,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String baseUrl,
      @Value("${clavaris.resilience.resend.failure-rate-threshold:50}")
          final float failureRateThreshold,
      @Value("${clavaris.resilience.resend.sliding-window-size:10}") final int slidingWindowSize,
      @Value("${clavaris.resilience.resend.wait-duration-in-open-state-seconds:30}")
          final long waitDurationInOpenStateSeconds,
      final SecurityMetricsRecorder metrics) {
    this(
        HttpClient.newHttpClient(),
        objectMapper,
        apiKey,
        fromAddress,
        baseUrl,
        DEFAULT_RESEND_ENDPOINT,
        buildCircuitBreaker(
            failureRateThreshold, slidingWindowSize, waitDurationInOpenStateSeconds, metrics));
  }

  // Test-only (TD-SEC-020): lets ResendMailSenderTest point this at a local stub HTTP server —
  // never the real Resend API — and inject a fully-controlled HttpClient to simulate IOException/
  // InterruptedException deterministically, without real network flakiness. Never invoked by
  // Spring itself; @Autowired on the constructor above resolves the ambiguity unambiguously. Same
  // parameter shape as before ResendHttpClient's own extraction — ResendMailSenderTest constructs
  // this directly and must keep working unmodified. Delegates to the full constructor below with a
  // bare-default CircuitBreaker (metrics binding only matters in production).
  /* package */ ResendMailSender(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final String apiKey,
      final String fromAddress,
      final String baseUrl,
      final URI resendEndpoint) {
    this(
        httpClient,
        objectMapper,
        apiKey,
        fromAddress,
        baseUrl,
        resendEndpoint,
        CircuitBreaker.ofDefaults("resend"));
  }

  @SuppressWarnings("java:S107")
  private ResendMailSender(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final String apiKey,
      final String fromAddress,
      final String baseUrl,
      final URI resendEndpoint,
      final CircuitBreaker circuitBreaker) {
    this.httpClient =
        new ResendHttpClient(
            httpClient, objectMapper, apiKey, fromAddress, resendEndpoint, circuitBreaker);
    this.baseUrl = baseUrl;
  }

  private static CircuitBreaker buildCircuitBreaker(
      final float failureRateThreshold,
      final int slidingWindowSize,
      final long waitDurationInOpenStateSeconds,
      final SecurityMetricsRecorder metrics) {
    final CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "resend",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
                .build());
    CircuitBreakerMetricsBinder.bind(circuitBreaker, metrics);
    return circuitBreaker;
  }

  @Override
  public void sendEmailVerification(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    httpClient.send(
        toAddress, Emails.verifyEmailLink(link(organizationId, "verify-email", rawToken), false));
  }

  @Override
  public void sendEmailVerificationCode(
      final String toAddress, final OrganizationId organizationId, final String rawCode) {
    httpClient.send(toAddress, Emails.verifyEmailCode(rawCode));
  }

  @Override
  public void sendPasswordReset(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    httpClient.send(
        toAddress, Emails.passwordReset(link(organizationId, "reset-password", rawToken), false));
  }

  @Override
  public void sendSocialLinkConfirmation(
      final String toAddress,
      final OrganizationId organizationId,
      final SocialProvider provider,
      final String rawToken) {
    httpClient.send(
        toAddress,
        Emails.socialLinkConfirmation(
            link(organizationId, "confirm-social-link", rawToken), provider, false));
  }

  @Override
  public void sendEmailSignInCode(
      final String toAddress, final OrganizationId organizationId, final String rawCode) {
    httpClient.send(toAddress, Emails.signInCode(rawCode));
  }

  @Override
  public void sendEmailSignInLink(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    httpClient.send(
        toAddress, Emails.signInLink(link(organizationId, "login/email-link", rawToken)));
  }

  @Override
  public void sendDeviceTrustChallengeCode(
      final String toAddress, final OrganizationId organizationId, final String rawCode) {
    httpClient.send(toAddress, Emails.deviceTrustCode(rawCode));
  }

  @Override
  public void sendNewDeviceLoginNotification(
      final String toAddress,
      final OrganizationId organizationId,
      final String userAgent,
      final String sourceIp,
      final Instant occurredAt,
      final String rawAlertToken) {
    // TD-FUT-025: a real "this wasn't me" action link when a token was minted (rawAlertToken !=
    // null), and a plain informational email when it was not - MailSender's own Javadoc documents
    // that a minting failure must never fail the whole send.
    //
    // userAgent and sourceIp are the first values that reach an email body which this server did
    // NOT generate: a raw HTTP request header, fully attacker-controlled. EmailRenderer escapes
    // every value it lays out, so they cannot inject markup into the sent email.
    final String lockLink =
        rawAlertToken == null ? null : link(organizationId, "account-alert/lock", rawAlertToken);
    httpClient.send(
        toAddress, Emails.newDeviceAlert(userAgent, sourceIp, occurredAt, lockLink, false));
  }

  @Override
  public void sendPlatformAccountEmailVerification(final String toAddress, final String rawToken) {
    httpClient.send(
        toAddress, Emails.verifyEmailLink(platformLink("verify-email", rawToken), true));
  }

  @Override
  public void sendPlatformSocialLinkConfirmation(
      final String toAddress, final SocialProvider provider, final String rawToken) {
    httpClient.send(
        toAddress,
        Emails.socialLinkConfirmation(
            platformLink("confirm-social-link", rawToken), provider, true));
  }

  @Override
  public void sendPlatformAccountPasswordReset(final String toAddress, final String rawToken) {
    httpClient.send(
        toAddress, Emails.passwordReset(platformLink("reset-password", rawToken), true));
  }

  @Override
  public void sendNewPlatformDeviceLoginNotification(
      final String toAddress,
      final String userAgent,
      final String sourceIp,
      final Instant occurredAt,
      final String rawAlertToken) {
    // Same escaping rationale as sendNewDeviceLoginNotification above. TD-FUT-031: same
    // real-link-when-minted, plain-when-not shape as that tenant-tier sibling.
    final String lockLink =
        rawAlertToken == null ? null : platformLink("account-alert/lock", rawAlertToken);
    httpClient.send(
        toAddress, Emails.newDeviceAlert(userAgent, sourceIp, occurredAt, lockLink, true));
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
}
