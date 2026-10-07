package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.LocalizedDateFormatter;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Detail;
import java.time.Instant;
import java.util.List;

/**
 * The emails Clavaris sends, one factory method each. A method says what the email is for (its
 * subject, its one action, how long it lasts); {@link EmailCopy} supplies the words in the reader's
 * language and {@link EmailRenderer} the layout. The tenant and platform tiers share a method and
 * differ only by the {@code platform} flag, which names "your Clavaris account" where a tenant
 * email says "your account".
 */
final class Emails {

  /** A finished email: the subject and the HTML and plain-text bodies. */
  /* default */ record Composed(String subject, String html, String text) {}

  private Emails() {
    // Static helpers only.
  }

  /** Confirm an email address with a link. */
  /* default */ static Composed verifyEmailLink(final String link, final boolean platform) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_VERIFY);
    final String lead =
        EmailCopy.text(platform ? EmailCopy.LEAD_VERIFY_PLAT : EmailCopy.PRE_VERIFY);
    return compose(
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_VERIFY))
            .paragraph(lead)
            .action(EmailCopy.text(EmailCopy.BTN_VERIFY), link)
            .note(EmailCopy.text(EmailCopy.NOTE_VLINK)));
  }

  /** Confirm an email address with a one-time code. */
  /* default */ static Composed verifyEmailCode(final String code) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_VERIFY);
    return compose(
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_VCODE))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_VCODE))
            .code(EmailCopy.text(EmailCopy.LBL_CODE), code)
            .note(EmailCopy.text(EmailCopy.NOTE_VCODE)));
  }

  /** Choose a new password. */
  /* default */ static Composed passwordReset(final String link, final boolean platform) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_RESET);
    final String lead = EmailCopy.text(platform ? EmailCopy.LEAD_RESET_PLAT : EmailCopy.LEAD_RESET);
    return compose(
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_RESET))
            .paragraph(lead)
            .action(EmailCopy.text(EmailCopy.BTN_RESET), link)
            .note(EmailCopy.text(EmailCopy.NOTE_RESET)));
  }

  /** Confirm connecting a Google or GitHub identity to an existing account. */
  /* default */ static Composed socialLinkConfirmation(
      final String link, final SocialProvider provider, final boolean platform) {
    final String name = EmailValues.providerName(provider);
    final String subject = EmailCopy.text(EmailCopy.SUBJ_SOCIAL, name);
    final String lead =
        EmailCopy.text(platform ? EmailCopy.LEAD_SOCIAL_PLAT : EmailCopy.LEAD_SOCIAL, name);
    return compose(
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_SOCIAL, name))
            .paragraph(lead)
            .action(EmailCopy.text(EmailCopy.BTN_SOCIAL), link)
            .note(EmailCopy.text(EmailCopy.NOTE_SOCIAL)));
  }

  /** Passwordless sign-in with a code. */
  /* default */ static Composed signInCode(final String code) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_CODE);
    return compose(
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_CODE))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_CODE))
            .code(EmailCopy.text(EmailCopy.LBL_CODE), code)
            .note(EmailCopy.text(EmailCopy.NOTE_CODE)));
  }

  /** Passwordless sign-in with a link. */
  /* default */ static Composed signInLink(final String link) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_LINK);
    return compose(
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_LINK))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_LINK))
            .action(EmailCopy.text(EmailCopy.BTN_SIGNIN), link)
            .note(EmailCopy.text(EmailCopy.NOTE_LINK)));
  }

  /** Confirm a sign-in from a device the account has not used before. */
  /* default */ static Composed deviceTrustCode(final String code) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_DEVICE);
    return compose(
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_DEVICE))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_DEVICE))
            .code(EmailCopy.text(EmailCopy.LBL_CODE), code)
            .note(EmailCopy.text(EmailCopy.NOTE_DEVICE)));
  }

  /**
   * Tell the account holder about a sign-in from a new device.
   *
   * @param lockLink the "this wasn't me" link, or {@code null} when none could be minted, in which
   *     case the email says to change the password instead
   */
  /* default */ static Composed newDeviceAlert(
      final String userAgent,
      final String sourceIp,
      final Instant occurredAt,
      final String lockLink,
      final boolean platform) {
    final String subject =
        EmailCopy.text(platform ? EmailCopy.SUBJ_ALERT_PLAT : EmailCopy.SUBJ_ALERT);
    final EmailContent content =
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_ALERT))
            .paragraph(EmailCopy.text(platform ? EmailCopy.LEAD_ALERT_PLAT : EmailCopy.LEAD_ALERT))
            .details(
                List.of(
                    new Detail(
                        EmailCopy.text(EmailCopy.LBL_DEVICE), EmailValues.orUnknown(userAgent)),
                    new Detail(EmailCopy.text(EmailCopy.LBL_IP), EmailValues.orUnknown(sourceIp)),
                    new Detail(
                        EmailCopy.text(EmailCopy.LBL_TIME),
                        LocalizedDateFormatter.dateTime(occurredAt))))
            .paragraph(EmailCopy.text(EmailCopy.ALERT_FINE));
    if (lockLink == null) {
      content.paragraph(EmailCopy.text(EmailCopy.ALERT_CHANGE));
    } else {
      content
          .paragraph(EmailCopy.text(EmailCopy.ALERT_LOCK))
          .dangerAction(EmailCopy.text(EmailCopy.BTN_LOCK), lockLink)
          .note(EmailCopy.text(EmailCopy.NOTE_ALERT));
    }
    return compose(subject, content);
  }

  private static Composed compose(final String subject, final EmailContent content) {
    return new Composed(
        subject,
        EmailRenderer.html(content, AppLocales.current().getLanguage()),
        EmailRenderer.text(content));
  }
}
