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
import org.springframework.web.util.HtmlUtils;
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

  // Both the tenant-tier LINK and CODE email-verification methods (ADR-0024 §2) and the
  // platform-tier equivalent share this exact subject line — one constant, not three repeated
  // literals.
  @SuppressWarnings("PMD.LongVariable")
  private static final String VERIFY_EMAIL_SUBJECT = "Verify your email address";

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
    final String link = link(organizationId, "verify-email", rawToken);
    httpClient.send(
        toAddress,
        VERIFY_EMAIL_SUBJECT,
        "<p>Confirm your email address to finish setting up your account:</p>"
            + ResendHttpClient.htmlButton(link, "Verify email")
            + "<p>This link expires in 24 hours. If you didn't request this, you can ignore it.</p>");
  }

  @Override
  public void sendEmailVerificationCode(
      final String toAddress, final OrganizationId organizationId, final String rawCode) {
    httpClient.send(
        toAddress,
        VERIFY_EMAIL_SUBJECT,
        "<p>Confirm your email address to finish setting up your account. Enter this code:</p>"
            + ResendHttpClient.htmlCode(rawCode)
            + "<p>This code expires in 24 hours. If you didn't request this, you can ignore it.</p>");
  }

  @Override
  public void sendPasswordReset(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    final String link = link(organizationId, "reset-password", rawToken);
    httpClient.send(
        toAddress,
        "Reset your password",
        "<p>A password reset was requested for this account:</p>"
            + ResendHttpClient.htmlButton(link, "Reset password")
            + "<p>This link expires in 30 minutes and can only be used once. If you didn't request"
            + " this, you can safely ignore it — your password will not be changed.</p>");
  }

  @Override
  public void sendSocialLinkConfirmation(
      final String toAddress,
      final OrganizationId organizationId,
      final SocialProvider provider,
      final String rawToken) {
    final String link = link(organizationId, "confirm-social-link", rawToken);
    httpClient.send(
        toAddress,
        "Confirm linking your " + provider + " account",
        "<p>Someone tried to sign in to this account using "
            + provider
            + ". If this was you, confirm the link:</p>"
            + ResendHttpClient.htmlButton(link, "Confirm link")
            + "<p>This link expires in 24 hours and can only be used once. If you didn't request"
            + " this, you can safely ignore it — no account changes will be made.</p>");
  }

  @Override
  public void sendEmailSignInCode(
      final String toAddress, final OrganizationId organizationId, final String rawCode) {
    httpClient.send(
        toAddress,
        "Your sign-in code",
        "<p>Enter this code to sign in:</p>"
            + ResendHttpClient.htmlCode(rawCode)
            + "<p>This code expires in 10 minutes. If you didn't request this, you can safely"
            + " ignore it — no one can sign in without it.</p>");
  }

  @Override
  public void sendEmailSignInLink(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    final String link = link(organizationId, "login/email-link", rawToken);
    httpClient.send(
        toAddress,
        "Your sign-in link",
        "<p>Click the button below to sign in:</p>"
            + ResendHttpClient.htmlButton(link, "Sign in")
            + "<p>This link expires in 10 minutes and can only be used once. If you didn't request"
            + " this, you can safely ignore it — no one can sign in without it.</p>");
  }

  @Override
  public void sendDeviceTrustChallengeCode(
      final String toAddress, final OrganizationId organizationId, final String rawCode) {
    httpClient.send(
        toAddress,
        "Confirm this new device",
        "<p>We don't recognize the device you're signing in from. Enter this code to confirm"
            + " it's you:</p>"
            + ResendHttpClient.htmlCode(rawCode)
            + "<p>This code expires in 10 minutes. If you didn't try to sign in, you can safely"
            + " ignore this — no one can complete the sign-in without it.</p>");
  }

  @Override
  public void sendNewDeviceLoginNotification(
      final String toAddress,
      final OrganizationId organizationId,
      final String userAgent,
      final String sourceIp,
      final Instant occurredAt,
      final String rawAlertToken) {
    // TD-FUT-025: renders a real "this wasn't me" action link when a token was minted
    // (rawAlertToken != null), and degrades to the old plain-informational body when it wasn't —
    // MailSender's own Javadoc documents that minting failure must never fail the whole send.
    //
    // userAgent/sourceIp are the first values this class has ever interpolated into an email body
    // that this server itself did NOT generate — a raw HTTP request header, fully attacker-
    // controlled. HtmlUtils.htmlEscape guards against HTML injection into the sent email; every
    // other send* method here only ever interpolates a link/token this server built itself, so
    // this is the first method that needs it.
    final String actionParagraph =
        rawAlertToken == null
            ? "<p>If this was you, no action is needed. If you don't recognize this activity,"
                + " change your password and review your active sessions.</p>"
            : "<p>If this was you, no action is needed.</p>"
                + "<p>If you don't recognize this activity, lock your account and sign out every"
                + " active session immediately:</p>"
                + ResendHttpClient.htmlButton(
                    link(organizationId, "account-alert/lock", rawAlertToken), "This wasn't me")
                + "<p>This link expires in 7 days and can only be used once.</p>";
    httpClient.send(
        toAddress,
        "New sign-in to your account",
        "<p>Your account was just signed in to from a new device or browser:</p>"
            + "<ul><li>Device: "
            + HtmlUtils.htmlEscape(userAgent)
            + "</li><li>IP address: "
            + HtmlUtils.htmlEscape(sourceIp)
            + "</li><li>Time: "
            + occurredAt
            + "</li></ul>"
            + actionParagraph);
  }

  @Override
  public void sendPlatformAccountEmailVerification(final String toAddress, final String rawToken) {
    final String link = platformLink("verify-email", rawToken);
    httpClient.send(
        toAddress,
        VERIFY_EMAIL_SUBJECT,
        "<p>Confirm your email address to finish setting up your Clavaris account:</p>"
            + ResendHttpClient.htmlButton(link, "Verify email")
            + "<p>This link expires in 24 hours. If you didn't request this, you can ignore it.</p>");
  }

  @Override
  public void sendPlatformSocialLinkConfirmation(
      final String toAddress, final SocialProvider provider, final String rawToken) {
    final String link = platformLink("confirm-social-link", rawToken);
    httpClient.send(
        toAddress,
        "Confirm linking your " + provider + " account",
        "<p>Someone tried to sign in to your Clavaris account using "
            + provider
            + ". If this was you, confirm the link:</p>"
            + ResendHttpClient.htmlButton(link, "Confirm link")
            + "<p>This link expires in 24 hours and can only be used once. If you didn't request"
            + " this, you can safely ignore it — no account changes will be made.</p>");
  }

  @Override
  public void sendPlatformAccountPasswordReset(final String toAddress, final String rawToken) {
    final String link = platformLink("reset-password", rawToken);
    httpClient.send(
        toAddress,
        "Reset your password",
        "<p>A password reset was requested for your Clavaris account:</p>"
            + ResendHttpClient.htmlButton(link, "Reset password")
            + "<p>This link expires in 30 minutes and can only be used once. If you didn't request"
            + " this, you can safely ignore it — your password will not be changed.</p>");
  }

  @Override
  public void sendNewPlatformDeviceLoginNotification(
      final String toAddress,
      final String userAgent,
      final String sourceIp,
      final Instant occurredAt,
      final String rawAlertToken) {
    // Same HtmlUtils.htmlEscape rationale as sendNewDeviceLoginNotification above — userAgent/
    // sourceIp are attacker-controlled raw request-header values, not something this server built.
    // TD-FUT-031: same real-link-when-minted/degrade-when-not shape as that tenant-tier sibling.
    final String actionParagraph =
        rawAlertToken == null
            ? "<p>If this was you, no action is needed. If you don't recognize this activity,"
                + " change your password and review your active sessions.</p>"
            : "<p>If this was you, no action is needed.</p>"
                + "<p>If you don't recognize this activity, lock your account and sign out every"
                + " active session immediately:</p>"
                + ResendHttpClient.htmlButton(
                    platformLink("account-alert/lock", rawAlertToken), "This wasn't me")
                + "<p>This link expires in 7 days and can only be used once.</p>";
    httpClient.send(
        toAddress,
        "New sign-in to your Clavaris account",
        "<p>Your Clavaris account was just signed in to from a new device or browser:</p>"
            + "<ul><li>Device: "
            + HtmlUtils.htmlEscape(userAgent)
            + "</li><li>IP address: "
            + HtmlUtils.htmlEscape(sourceIp)
            + "</li><li>Time: "
            + occurredAt
            + "</li></ul>"
            + actionParagraph);
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
