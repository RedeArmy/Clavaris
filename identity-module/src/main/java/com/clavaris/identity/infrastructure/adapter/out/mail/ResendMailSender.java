package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.identity.application.usecase.requestemailverification.MailSender;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.PlatformMailSender;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
// Implements both MailSender (4 send* methods) and PlatformMailSender (3 more) in one class —
// deliberately, same "one class, same HTTP mechanics, two ports" design this class's own Javadoc
// already explains. No PMD.TooManyMethods suppression needed any more: extracting the shared HTTP
// mechanics to ResendHttpClient (code review finding, 2026-09-01) brought this class's own method
// count back under PMD's default threshold.
@Component
class ResendMailSender implements MailSender, PlatformMailSender {

  @SuppressWarnings("PMD.LongVariable")
  private static final URI DEFAULT_RESEND_ENDPOINT = URI.create("https://api.resend.com/emails");

  private final ResendHttpClient httpClient;
  private final String baseUrl;

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
  // Spring itself; @Autowired on the constructor above resolves the ambiguity unambiguously. Same
  // parameter shape as before ResendHttpClient's own extraction — ResendMailSenderTest constructs
  // this directly and must keep working unmodified.
  /* package */ ResendMailSender(
      final HttpClient httpClient,
      final ObjectMapper objectMapper,
      final String apiKey,
      final String fromAddress,
      final String baseUrl,
      final URI resendEndpoint) {
    this.httpClient =
        new ResendHttpClient(httpClient, objectMapper, apiKey, fromAddress, resendEndpoint);
    this.baseUrl = baseUrl;
  }

  @Override
  public void sendEmailVerification(
      final String toAddress, final OrganizationId organizationId, final String rawToken) {
    final String link = link(organizationId, "verify-email", rawToken);
    httpClient.send(
        toAddress,
        "Verify your email address",
        "<p>Confirm your email address to finish setting up your account:</p>"
            + ResendHttpClient.htmlButton(link, "Verify email")
            + "<p>This link expires in 24 hours. If you didn't request this, you can ignore it.</p>");
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
  public void sendNewDeviceLoginNotification(
      final String toAddress,
      final OrganizationId organizationId,
      final String userAgent,
      final String sourceIp,
      final Instant occurredAt) {
    // Plain informational email, no action link — see MailSender's own Javadoc for why a "this
    // wasn't me" flow is deliberately out of scope for now. organizationId is accepted (matches
    // every other tenant-tier method's own signature here) but unused in the body itself: the
    // recipient already knows which product they're logging into, this notification doesn't need
    // to brand itself per-Organization the way a confirmation link's own redirect target does.
    //
    // userAgent/sourceIp are the first values this class has ever interpolated into an email body
    // that this server itself did NOT generate — a raw HTTP request header, fully attacker-
    // controlled. HtmlUtils.htmlEscape guards against HTML injection into the sent email; every
    // other send* method here only ever interpolates a link/token this server built itself, so
    // this is the first method that needs it.
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
            + "<p>If this was you, no action is needed. If you don't recognize this activity,"
            + " change your password and review your active sessions.</p>");
  }

  @Override
  public void sendPlatformAccountEmailVerification(final String toAddress, final String rawToken) {
    final String link = platformLink("verify-email", rawToken);
    httpClient.send(
        toAddress,
        "Verify your email address",
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
