package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.LocalizedDateFormatter;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.infrastructure.adapter.out.mail.EmailContent.Detail;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The emails Clavaris sends, one factory method each. A method says what the email is for (its
 * subject, its one action, how long it lasts); {@link EmailCopy} supplies the words in the reader's
 * language and {@link EmailRenderer} the layout. The tenant and platform tiers share a method and
 * differ by the {@code platform} flag, which names "your Clavaris account" where a tenant email
 * says "your account", and by the {@code brand}: the name an email is sent in. A platform email is
 * Clavaris's own ({@link #PLATFORM_BRAND}); a tenant email is its Organization's, or nobody's when
 * that name is unknown ({@code null}), and never Clavaris's.
 */
// PMD.TooManyMethods: one builder per distinct email (TD-FUT-043 added three more).
@SuppressWarnings("PMD.TooManyMethods")
final class Emails {

  private static final int MINUTES_PER_HOUR = 60;

  /** The name Clavaris's own emails (to platform accounts) are sent in. */
  /* default */ static final String PLATFORM_BRAND = "Clavaris";

  /** A finished email: the subject and the HTML and plain-text bodies. */
  /* default */ record Composed(String subject, String html, String text) {}

  private Emails() {
    // Static helpers only.
  }

  /** Confirm an email address with a link. */
  /* default */ static Composed verifyEmailLink(
      final String brand, final String link, final boolean platform) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_VERIFY);
    final String lead =
        EmailCopy.text(platform ? EmailCopy.LEAD_VERIFY_PLAT : EmailCopy.PRE_VERIFY);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_VERIFY))
            .paragraph(lead)
            .action(EmailCopy.text(EmailCopy.BTN_VERIFY), link)
            .note(EmailCopy.text(EmailCopy.NOTE_VLINK)));
  }

  /** Confirm an email address with a one-time code. */
  /* default */ static Composed verifyEmailCode(final String brand, final String code) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_VERIFY);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_VCODE))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_VCODE))
            .code(EmailCopy.text(EmailCopy.LBL_CODE), code)
            .note(EmailCopy.text(EmailCopy.NOTE_VCODE)));
  }

  /** Choose a new password. */
  /* default */ static Composed passwordReset(
      final String brand, final String link, final boolean platform) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_RESET);
    final String lead = EmailCopy.text(platform ? EmailCopy.LEAD_RESET_PLAT : EmailCopy.LEAD_RESET);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_RESET))
            .paragraph(lead)
            .action(EmailCopy.text(EmailCopy.BTN_RESET), link)
            .note(EmailCopy.text(EmailCopy.NOTE_RESET)));
  }

  /** Confirm connecting a Google or GitHub identity to an existing account. */
  /* default */ static Composed socialLinkConfirmation(
      final String brand,
      final String link,
      final SocialProvider provider,
      final boolean platform) {
    final String name = EmailValues.providerName(provider);
    final String subject = EmailCopy.text(EmailCopy.SUBJ_SOCIAL, name);
    final String lead =
        EmailCopy.text(platform ? EmailCopy.LEAD_SOCIAL_PLAT : EmailCopy.LEAD_SOCIAL, name);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_SOCIAL, name))
            .paragraph(lead)
            .action(EmailCopy.text(EmailCopy.BTN_SOCIAL), link)
            .note(EmailCopy.text(EmailCopy.NOTE_SOCIAL)));
  }

  /** Passwordless sign-in with a code. */
  /* default */ static Composed signInCode(final String brand, final String code) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_CODE);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_CODE))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_CODE))
            .code(EmailCopy.text(EmailCopy.LBL_CODE), code)
            .note(EmailCopy.text(EmailCopy.NOTE_CODE)));
  }

  /** Passwordless sign-in with a link. */
  /* default */ static Composed signInLink(final String brand, final String link) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_LINK);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_LINK))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_LINK))
            .action(EmailCopy.text(EmailCopy.BTN_SIGNIN), link)
            .note(EmailCopy.text(EmailCopy.NOTE_LINK)));
  }

  /** Confirm a sign-in from a device the account has not used before. */
  /* default */ static Composed deviceTrustCode(final String brand, final String code) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_DEVICE);
    return compose(
        brand,
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
      final String brand,
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
    return compose(brand, subject, content);
  }

  /** The password was changed from a signed-in session (TD-FUT-043). */
  /* default */ static Composed passwordChanged(final String brand) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_PW_CHANGED);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_PW_CHANGED))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_PW_CHANGED))
            .paragraph(EmailCopy.text(EmailCopy.ALERT_FINE))
            .note(EmailCopy.text(EmailCopy.NOTE_PW_CHANGED)));
  }

  /** Codes sent by email were paused for the Account after too many wrong ones (ADR-0033). */
  /* default */ static Composed codeLocked(final String brand, final Duration lockedFor) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_CODE_LOCKED);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_CODE_LOCKED))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_CODE_LOCKED, humanize(lockedFor)))
            .note(EmailCopy.text(EmailCopy.NOTE_CODE_LOCKED)));
  }

  // "15 minutes", "1 hour", "2 hours": the lock is a round number of minutes, and a mail does not
  // need more precision than that. Through the catalogue, so the unit is in the reader's language.
  private static String humanize(final Duration duration) {
    final long minutes = Math.max(1, duration.toMinutes());
    if (minutes < MINUTES_PER_HOUR) {
      return minutes == 1
          ? EmailCopy.text(EmailCopy.DUR_MINUTE)
          : EmailCopy.text(EmailCopy.DUR_MINUTES, minutes);
    }
    final long hours = Math.round(minutes / (double) MINUTES_PER_HOUR);
    return hours == 1
        ? EmailCopy.text(EmailCopy.DUR_HOUR)
        : EmailCopy.text(EmailCopy.DUR_HOURS, hours);
  }

  /** To the new address: confirm it is yours (TD-FUT-043). */
  /* default */ static Composed emailChangeVerification(final String brand, final String link) {
    final String subject = EmailCopy.text(EmailCopy.SUBJ_EMAIL_CHANGE);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, EmailCopy.text(EmailCopy.PRE_EMAIL_CHANGE))
            .paragraph(EmailCopy.text(EmailCopy.LEAD_EMAIL_CHANGE))
            .action(EmailCopy.text(EmailCopy.BTN_EMAIL_CHANGE), link)
            .note(EmailCopy.text(EmailCopy.NOTE_EMAIL_CHANGE)));
  }

  /** To the current address: a change to {@code newAddress} was asked for, or has happened. */
  /* default */ static Composed emailChangeNotice(
      final String brand, final String newAddress, final boolean completed) {
    final String subject =
        EmailCopy.text(completed ? EmailCopy.SUBJ_EMAIL_DONE : EmailCopy.SUBJ_EMAIL_ASKED);
    final String preview =
        EmailCopy.text(completed ? EmailCopy.PRE_EMAIL_DONE : EmailCopy.PRE_EMAIL_ASKED);
    final String lead =
        EmailCopy.text(
            completed ? EmailCopy.LEAD_EMAIL_DONE : EmailCopy.LEAD_EMAIL_ASKED, newAddress);
    return compose(
        brand,
        subject,
        EmailContent.titled(subject, preview)
            .paragraph(lead)
            .note(EmailCopy.text(EmailCopy.NOTE_EMAIL_NOT_YOU)));
  }

  private static Composed compose(
      final String brand, final String subject, final EmailContent content) {
    return new Composed(
        subject,
        EmailRenderer.html(content, brand, AppLocales.current().getLanguage()),
        EmailRenderer.text(content, brand));
  }
}
