package com.clavaris.identity.infrastructure.adapter.out.mail;

import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.MessageCatalogs;

/**
 * Every sentence a Clavaris email can say, in English, and the one place that turns it into the
 * reader's language.
 *
 * <p>The English text is the key into the same gettext-style catalogue the pages use (see {@code
 * docs/05-engineering/internationalization.md}); the email entries carry the context {@code email}
 * so they never collide with a page entry that happens to read the same. A placeholder such as
 * {@code {0}} is looked up as written and filled in afterwards, so a translation can move it. A
 * text with no translation falls back to English.
 *
 * <p>The language is the one of the request being served: every email is sent while the person who
 * will read it is waiting on that request (registering, asking for a reset, signing in), so their
 * language is already known.
 */
final class EmailCopy {

  private static final String CONTEXT = "email";

  // Subjects and titles.
  /* default */ static final String SUBJ_VERIFY = "Verify your email address";
  /* default */ static final String SUBJ_RESET = "Reset your password";
  /* default */ static final String SUBJ_SOCIAL = "Confirm linking your {0} account";
  /* default */ static final String SUBJ_CODE = "Your sign-in code";
  /* default */ static final String SUBJ_LINK = "Your sign-in link";
  /* default */ static final String SUBJ_DEVICE = "Confirm this new device";
  /* default */ static final String SUBJ_ALERT = "New sign-in to your account";
  /* default */ static final String SUBJ_ALERT_PLAT = "New sign-in to your Clavaris account";

  // The preview line mail apps show beside the subject.
  /* default */ static final String PRE_VERIFY =
      "Confirm your email address to finish setting up your account.";
  /* default */ static final String PRE_VCODE = "Use this code to finish setting up your account.";
  /* default */ static final String PRE_RESET = "Choose a new password for your account.";
  /* default */ static final String PRE_SOCIAL =
      "Confirm that you want to connect your {0} account.";
  /* default */ static final String PRE_CODE =
      "Use this code to sign in. It expires in 10 minutes.";
  /* default */ static final String PRE_LINK =
      "Use this link to sign in. It expires in 10 minutes.";
  /* default */ static final String PRE_DEVICE =
      "Enter this code to confirm it's you. It expires in 10 minutes.";
  /* default */ static final String PRE_ALERT =
      "A new device or browser signed in to your account.";

  // The opening sentence of the body.
  /* default */ static final String LEAD_VERIFY_PLAT =
      "Confirm your email address to finish setting up your Clavaris account.";
  /* default */ static final String LEAD_VCODE =
      "Enter this code to finish setting up your account.";
  /* default */ static final String LEAD_RESET =
      "We received a request to reset the password for your account.";
  /* default */ static final String LEAD_RESET_PLAT =
      "We received a request to reset the password for your Clavaris account.";
  /* default */ static final String LEAD_SOCIAL =
      "Someone tried to sign in to your account with {0}. If that was you, confirm the link to"
          + " connect the two accounts.";
  /* default */ static final String LEAD_SOCIAL_PLAT =
      "Someone tried to sign in to your Clavaris account with {0}. If that was you, confirm the"
          + " link to connect the two accounts.";
  /* default */ static final String LEAD_CODE = "Enter this code to sign in.";
  /* default */ static final String LEAD_LINK = "Use the button below to sign in.";
  /* default */ static final String LEAD_DEVICE =
      "We don't recognize the device you're signing in from. Enter this code to confirm it's you.";
  /* default */ static final String LEAD_ALERT =
      "Your account was just signed in to from a new device or browser.";
  /* default */ static final String LEAD_ALERT_PLAT =
      "Your Clavaris account was just signed in to from a new device or browser.";

  // What to do after a new-device sign-in.
  /* default */ static final String ALERT_FINE = "If this was you, no action is needed.";
  /* default */ static final String ALERT_LOCK =
      "If you don't recognize this activity, lock your account and sign out of every active"
          + " session now.";
  /* default */ static final String ALERT_CHANGE =
      "If you don't recognize this activity, change your password and review your active sessions.";

  // Buttons and labels.
  /* default */ static final String BTN_VERIFY = "Verify email";
  /* default */ static final String BTN_RESET = "Reset password";
  /* default */ static final String BTN_SOCIAL = "Confirm link";
  /* default */ static final String BTN_SIGNIN = "Sign in";
  /* default */ static final String BTN_LOCK = "This wasn't me";
  /* default */ static final String LBL_CODE = "Your code";
  /* default */ static final String LBL_DEVICE = "Device";
  /* default */ static final String LBL_IP = "IP address";
  /* default */ static final String LBL_TIME = "Time";

  // The small print: when it expires and what to do if it was not you.
  /* default */ static final String NOTE_VLINK =
      "This link expires in 24 hours. If you didn't request this, you can safely ignore this"
          + " email.";
  /* default */ static final String NOTE_VCODE =
      "This code expires in 24 hours. If you didn't request this, you can safely ignore this"
          + " email.";
  /* default */ static final String NOTE_RESET =
      "This link expires in 30 minutes and can be used once. If you didn't request this, ignore"
          + " this email: your password will not change.";
  /* default */ static final String NOTE_SOCIAL =
      "This link expires in 24 hours and can be used once. If you didn't request this, ignore"
          + " this email: nothing will change.";
  /* default */ static final String NOTE_CODE =
      "This code expires in 10 minutes. If you didn't request this, ignore this email: nobody can"
          + " sign in without it.";
  /* default */ static final String NOTE_LINK =
      "This link expires in 10 minutes and can be used once. If you didn't request this, ignore"
          + " this email: nobody can sign in without it.";
  /* default */ static final String NOTE_DEVICE =
      "This code expires in 10 minutes. If you didn't try to sign in, ignore this email: nobody"
          + " can finish signing in without it.";
  /* default */ static final String NOTE_ALERT =
      "This link expires in 7 days and can be used once.";

  // Around every email.
  /* default */ static final String FALLBACK =
      "If the button does not work, copy and paste this link into your browser:";
  /* default */ static final String FOOTER =
      "Sent by Clavaris. This is an automated message, so replies are not monitored.";

  private EmailCopy() {
    // Static helpers only.
  }

  /**
   * The sentence in the current language, with {@code {0}}, {@code {1}}… replaced by {@code args}.
   */
  /* default */ static String text(final String english, final Object... args) {
    String result =
        MessageCatalogs.forLocale(AppLocales.current()).translate(CONTEXT, english).orElse(english);
    for (int index = 0; index < args.length; index++) {
      result = result.replace("{" + index + "}", String.valueOf(args[index]));
    }
    return result;
  }
}
