package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;

/**
 * Outbound port — implemented by {@code infrastructure/adapter/out/mail/ResendMailSender}. One port
 * for both email kinds this system sends (mirrors {@code VerificationToken} itself being one model
 * for both), rather than two near-identical adapters — {@code requestpasswordreset} reuses this
 * same interface, same precedent as {@code registeraccount.EventOutboxWriter}.
 *
 * <p>Deliberately takes the raw token, not a fully-built URL: building {@code
 * {clavarisBaseUrl}/o/{organizationId}/...} is an HTTP-routing concern the application layer must
 * not know about (§7.2's dependency rule) — the adapter, which already knows its own deployment's
 * {@code CLAVARIS_BASE_URL}, is where that link actually gets assembled.
 */
public interface MailSender {

  void sendEmailVerification(String toAddress, OrganizationId organizationId, String rawToken);

  void sendPasswordReset(String toAddress, OrganizationId organizationId, String rawToken);

  /**
   * ADR-0020 Decision 1, BR-ID-09: {@code AuthenticateWithSocialProviderService}'s confirmation
   * step — delivered only to the account's existing email of record, never to whatever address the
   * social provider itself reported (that's the entire point: proving the account holder still
   * controls the email a pre-existing account was registered with).
   */
  void sendSocialLinkConfirmation(
      String toAddress, OrganizationId organizationId, SocialProvider provider, String rawToken);
}
