package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

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
}
