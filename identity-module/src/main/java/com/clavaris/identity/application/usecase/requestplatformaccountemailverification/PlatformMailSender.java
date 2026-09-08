package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

import com.clavaris.identity.domain.model.SocialProvider;
import java.time.Instant;

/**
 * Outbound port — implemented by {@code infrastructure/adapter/out/mail/ResendMailSender} (the same
 * adapter class as {@code requestemailverification.MailSender}, implementing both interfaces).
 * Deliberately a SEPARATE interface from {@code MailSender}, not two more methods bolted onto it:
 * {@code MailSender}'s methods are tenant-branded (take an {@code OrganizationId}, subject/body use
 * that Organization's name); these are generic "Clavaris"-branded, since a {@code PlatformAccount}
 * belongs to no Organization to brand with — same "narrow, single-purpose ports" convention already
 * applied to {@code PasswordHasher} vs. {@code PasswordVerifier}.
 */
public interface PlatformMailSender {

  void sendPlatformAccountEmailVerification(String toAddress, String rawToken);

  void sendPlatformAccountPasswordReset(String toAddress, String rawToken);

  /**
   * ADR-0020 Decision 1, BR-ID-09: {@code AuthenticatePlatformAccountWithSocialProviderService}'s
   * confirmation step — same "delivered only to the account's existing email of record" rationale
   * as {@code MailSender.sendSocialLinkConfirmation}.
   */
  void sendPlatformSocialLinkConfirmation(
      String toAddress, SocialProvider provider, String rawToken);

  /**
   * TD-FUT-026 (closed 2026-09-02): {@code recordplatformaccountlogindevice.
   * RecordPlatformAccountLoginDeviceService}'s own notification — the platform-tier mirror of
   * {@code requestemailverification.MailSender.sendNewDeviceLoginNotification}, generic
   * "Clavaris"-branded like every other method here, since a {@code PlatformAccount} belongs to no
   * Organization to brand with.
   *
   * <p><b>TD-FUT-031 (closed):</b> {@code rawAlertToken} carries the same "this wasn't me" contract
   * as that tenant-tier sibling's own now-added parameter — the raw {@code VerificationToken} value
   * for {@code VerificationTokenType#NEW_DEVICE_LOGIN_ALERT}, or {@code null} when minting it
   * failed, in which case implementations must render the old plain-informational body with no
   * action link rather than fail the whole send.
   */
  void sendNewPlatformDeviceLoginNotification(
      String toAddress,
      String userAgent,
      String sourceIp,
      Instant occurredAt,
      String rawAlertToken);
}
