package com.clavaris.app.infrastructure.adapter.in.web.filter;

import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * The {@link RateLimitRule}s of the default security chain: the hosted pages that send a message or
 * take a code (sign-in by emailed code or link, the new-device and authenticator-app challenges,
 * email verification, forgot-password). They are configuration data, so they live here, as {@link
 * OrganizationRateLimitRules} and {@link AdminApiRateLimitRules} do for their chains, and a test
 * can read them: {@code LoginEndpointsRateLimitCoverageTest} fails when a POST endpoint under
 * {@code /o/{organizationId}/login/} has no rule anywhere.
 */
public final class HostedPageRateLimitRules {

  private HostedPageRateLimitRules() {
    // Static factory only — see this class's own Javadoc.
  }

  // One parameter per tunable limit, as in OrganizationRateLimitRules.
  public static List<RateLimitRule> all(
      final int emailCodeConfirmPerEmailLimit,
      final int emailRequestPerEmailLimit,
      final int emailRequestPerIpLimit,
      final int codeConfirmPerIpLimit,
      final int webauthnPerIpLimit) {
    return List.of(
        new RateLimitRule(
            "login-email-code-confirm:email",
            HttpMethod.POST,
            "/o/*/login/email-code/confirm",
            RateLimitRule.always(),
            RateLimitIdentifiers::emailFormField,
            emailCodeConfirmPerEmailLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "verify-email-code:email",
            HttpMethod.POST,
            "/o/*/verify-email/code",
            RateLimitRule.always(),
            RateLimitIdentifiers::emailFormField,
            emailCodeConfirmPerEmailLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "verify-email-code:ip",
            HttpMethod.POST,
            "/o/*/verify-email/code",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            codeConfirmPerIpLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "login-email-link-confirm:ip",
            HttpMethod.POST,
            "/o/*/login/email-link/confirm",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            codeConfirmPerIpLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "login-device-trust-confirm:ip",
            HttpMethod.POST,
            "/o/*/login/device-trust",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            codeConfirmPerIpLimit,
            Duration.ofMinutes(5)),
        // TD-SEC-085: the authenticator-app challenge. The Account is in the session, so
        // there is no address to count; this is the outer layer by source IP, over what the
        // per-Account lockout (ADR-0033, ADR-0040) already does inside.
        new RateLimitRule(
            "login-totp-confirm:ip",
            HttpMethod.POST,
            "/o/*/login/totp",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            codeConfirmPerIpLimit,
            Duration.ofMinutes(5)),
        // TD-SEC-085: the two steps of a passkey sign-in are public. Start opens a challenge in the
        // session on every page load that offers passkeys (the browser may call it for autofill),
        // so
        // it has its own, more generous limit: an office behind one address must not lose passkey
        // sign-in because a few dozen people opened the page in the same five minutes.
        new RateLimitRule(
            "login-webauthn:ip",
            HttpMethod.POST,
            "/o/*/login/webauthn/**",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            webauthnPerIpLimit,
            Duration.ofMinutes(5)),
        // The steps taken after the primary factor (set a username, set a new password). The
        // password
        // step asks the breached-password service, a real outbound call, so a session that is
        // allowed
        // to repeat it without bound is a way to spend that service's quota.
        new RateLimitRule(
            "login-session-task:ip",
            HttpMethod.POST,
            "/o/*/login/session-task/**",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            codeConfirmPerIpLimit,
            Duration.ofMinutes(5)),
        new RateLimitRule(
            "login-email-code-request:email",
            HttpMethod.POST,
            "/o/*/login/email-code",
            RateLimitRule.always(),
            RateLimitIdentifiers::emailFormField,
            emailRequestPerEmailLimit,
            Duration.ofMinutes(10)),
        new RateLimitRule(
            "login-email-code-request:ip",
            HttpMethod.POST,
            "/o/*/login/email-code",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            emailRequestPerIpLimit,
            Duration.ofMinutes(10)),
        new RateLimitRule(
            "login-email-link-request:email",
            HttpMethod.POST,
            "/o/*/login/email-link",
            RateLimitRule.always(),
            RateLimitIdentifiers::emailFormField,
            emailRequestPerEmailLimit,
            Duration.ofMinutes(10)),
        // ADR-0038: asking for another verification email sends a real message, so it
        // is
        // limited like the other requests that do, per address and per source IP.
        new RateLimitRule(
            "verify-email-resend:email",
            HttpMethod.POST,
            "/o/*/verify-email/resend",
            RateLimitRule.always(),
            RateLimitIdentifiers::emailFormField,
            emailRequestPerEmailLimit,
            Duration.ofMinutes(10)),
        new RateLimitRule(
            "verify-email-resend:ip",
            HttpMethod.POST,
            "/o/*/verify-email/resend",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            emailRequestPerIpLimit,
            Duration.ofMinutes(10)),
        new RateLimitRule(
            "forgot-password-request:email",
            HttpMethod.POST,
            "/o/*/forgot-password",
            RateLimitRule.always(),
            RateLimitIdentifiers::emailFormField,
            emailRequestPerEmailLimit,
            Duration.ofMinutes(10)),
        new RateLimitRule(
            "forgot-password-request:ip",
            HttpMethod.POST,
            "/o/*/forgot-password",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            emailRequestPerIpLimit,
            Duration.ofMinutes(10)),
        new RateLimitRule(
            "login-email-link-request:ip",
            HttpMethod.POST,
            "/o/*/login/email-link",
            RateLimitRule.always(),
            RateLimitIdentifiers::sourceIp,
            emailRequestPerIpLimit,
            Duration.ofMinutes(10)));
  }
}
