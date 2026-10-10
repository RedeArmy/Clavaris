package com.clavaris.identity.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.i18n.AppLocales;
import com.clavaris.common.i18n.MessageCatalog;
import com.clavaris.common.i18n.MessageCatalogs;
import com.clavaris.identity.domain.model.SocialProvider;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Covers what every Clavaris email shares: the layout, the plain-text twin, escaping, the formatted
 * time, and that each sentence has a Spanish translation with the same placeholders.
 */
class EmailsTest {

  private static final String LINK = "https://id.example.com/o/42/verify-email?token=abc%2Bdef";
  private static final Instant AT = Instant.parse("2026-08-31T10:00:00Z");
  // The name a tenant email is sent in (its Organization's) and the platform's own.
  private static final String ORG = "Acme Analytics";
  private static final String PLATFORM = Emails.PLATFORM_BRAND;
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

  @AfterEach
  void clearLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  private static List<Emails.Composed> tenantEmails() {
    return List.of(
        Emails.verifyEmailLink(ORG, LINK, false),
        Emails.verifyEmailCode(ORG, "482913"),
        Emails.passwordReset(ORG, LINK, false),
        Emails.socialLinkConfirmation(ORG, LINK, SocialProvider.GOOGLE, false),
        Emails.signInCode(ORG, "111222"),
        Emails.signInLink(ORG, LINK),
        Emails.deviceTrustCode(ORG, "999888"),
        Emails.newDeviceAlert(ORG, "Mozilla/5.0 Test Browser", "203.0.113.5", AT, LINK, false));
  }

  private static List<Emails.Composed> platformEmails() {
    return List.of(
        Emails.verifyEmailLink(PLATFORM, LINK, true),
        Emails.passwordReset(PLATFORM, LINK, true),
        Emails.socialLinkConfirmation(PLATFORM, LINK, SocialProvider.GITHUB, true),
        Emails.newDeviceAlert(PLATFORM, "Mozilla/5.0 Test Browser", "203.0.113.5", AT, null, true));
  }

  private static List<Emails.Composed> everyEmail() {
    final List<Emails.Composed> all = new java.util.ArrayList<>(tenantEmails());
    all.addAll(platformEmails());
    return all;
  }

  @Test
  void everyEmailIsACompleteDocumentWithAPreviewLineAndNoImages() {
    assertThat(everyEmail())
        .allSatisfy(
            email ->
                assertThat(email.html())
                    .startsWith("<!doctype html><html lang=\"en\">")
                    .contains("<title>" + email.subject().replace("'", "&#39;") + "</title>")
                    .contains("name=\"viewport\"")
                    .contains("name=\"color-scheme\"")
                    .contains("prefers-color-scheme:dark")
                    .contains("display:none;max-height:0")
                    .doesNotContain("<img")
                    .doesNotContain("<script"));
  }

  @Test
  void everyEmailHasAPlainTextTwinWithNoMarkup() {
    assertThat(everyEmail())
        .allSatisfy(
            email ->
                assertThat(email.text())
                    .contains(email.subject())
                    .doesNotContain("<")
                    .doesNotContain("&#")
                    .contains("Sent by "));
  }

  @Test
  void aLinkEmailCarriesItsLinkAsAButtonAndAsPlainText() {
    final Emails.Composed email = Emails.verifyEmailLink(ORG, LINK, false);

    assertThat(email.html())
        .contains("<a href=\"" + LINK + "\"")
        .contains("Verify email")
        .contains("copy and paste this link");
    assertThat(email.text()).contains("Verify email: " + LINK);
  }

  @Test
  void aCodeEmailShowsTheCodeLargeAndInThePlainText() {
    final Emails.Composed email = Emails.signInCode(ORG, "111222");

    assertThat(email.html()).contains(">111222</div>").contains("letter-spacing:0.3em");
    assertThat(email.text()).contains("Your code: 111222");
    assertThat(email.subject()).doesNotContain("111222");
  }

  @Test
  void theNewDeviceAlertShowsADangerActionOnlyWhenThereIsALinkToLockTheAccount() {
    final Emails.Composed withLink =
        Emails.newDeviceAlert(ORG, "Mozilla/5.0", "203.0.113.5", AT, LINK, false);
    final Emails.Composed withoutLink =
        Emails.newDeviceAlert(ORG, "Mozilla/5.0", "203.0.113.5", AT, null, false);

    assertThat(withLink.html()).contains("This wasn&#39;t me").contains("#dc2626");
    assertThat(withLink.text()).contains("This wasn't me: " + LINK);
    assertThat(withoutLink.html()).doesNotContain("wasn&#39;t me").doesNotContain("#dc2626");
    assertThat(withoutLink.text()).contains("change your password");
  }

  @Test
  void theSignInTimeIsReadableNotARawTimestamp() {
    final Emails.Composed email =
        Emails.newDeviceAlert(ORG, "Mozilla/5.0", "1.2.3.4", AT, null, false);

    assertThat(email.html()).contains("August 31, 2026 at 10:00 UTC").doesNotContain("2026-08-31T");
    assertThat(email.text()).contains("Time: August 31, 2026 at 10:00 UTC");
  }

  @Test
  void aMissingDeviceOrAddressReadsAsADashRatherThanTheWordNull() {
    final Emails.Composed email = Emails.newDeviceAlert(ORG, null, " ", AT, null, false);

    assertThat(email.text()).contains("Device: —").contains("IP address: —").doesNotContain("null");
  }

  @Test
  void valuesTheServerDidNotWriteAreEscaped() {
    final Emails.Composed email =
        Emails.newDeviceAlert(ORG, "<script>alert(1)</script>", "1.2.3.4\"><b>", AT, null, false);

    assertThat(email.html())
        .doesNotContain("<script>")
        .doesNotContain("<b>")
        .contains("&lt;script&gt;");
  }

  @Test
  void aSocialProviderIsNamedByItsBrandNotItsConstant() {
    assertThat(Emails.socialLinkConfirmation(ORG, LINK, SocialProvider.GOOGLE, false).subject())
        .isEqualTo("Confirm linking your Google account");
    assertThat(Emails.socialLinkConfirmation(PLATFORM, LINK, SocialProvider.GITHUB, true).subject())
        .isEqualTo("Confirm linking your GitHub account");
  }

  @Test
  void aPlatformEmailNamesTheClavarisAccountWhereATenantEmailSaysYourAccount() {
    assertThat(Emails.passwordReset(PLATFORM, LINK, true).text()).contains("your Clavaris account");
    assertThat(Emails.passwordReset(ORG, LINK, false).text())
        .contains("for your account.")
        .doesNotContain("your Clavaris account");
  }

  // --- Spanish ---------------------------------------------------------------------------------

  @Test
  void inSpanishEveryEmailIsSpanishFromSubjectToFooter() {
    LocaleContextHolder.setLocale(AppLocales.SPANISH);

    assertThat(everyEmail())
        .allSatisfy(
            email -> {
              assertThat(email.html()).startsWith("<!doctype html><html lang=\"es\">");
              assertThat(email.subject() + email.html() + email.text())
                  .doesNotContain("expires")
                  .doesNotContain("ignore this email")
                  .doesNotContain("copy and paste")
                  .doesNotContain("Sent by")
                  .doesNotContain("Your code")
                  .doesNotContain("IP address");
              // Tenant emails are sent in the Organization's name, platform ones in Clavaris's.
              assertThat(email.text()).containsPattern("Enviado por (Acme Analytics|Clavaris)\\.");
            });
  }

  @Test
  void aSpanishEmailKeepsItsSubjectLinkAndCode() {
    LocaleContextHolder.setLocale(Locale.forLanguageTag("es-GT"));

    assertThat(Emails.verifyEmailLink(ORG, LINK, false).subject())
        .isEqualTo("Verifica tu correo electrónico");
    assertThat(Emails.verifyEmailLink(ORG, LINK, false).html())
        .contains("<a href=\"" + LINK + "\"");
    assertThat(Emails.signInCode(ORG, "111222").text()).contains("Tu código: 111222");
    assertThat(Emails.socialLinkConfirmation(ORG, LINK, SocialProvider.GITHUB, false).subject())
        .isEqualTo("Confirma la vinculación de tu cuenta de GitHub");
  }

  @Test
  void theSignInTimeFollowsTheLanguage() {
    LocaleContextHolder.setLocale(AppLocales.SPANISH);

    assertThat(Emails.newDeviceAlert(ORG, "Mozilla/5.0", "1.2.3.4", AT, null, false).text())
        .contains("Hora: 31 de agosto de 2026, 10:00 UTC");
  }

  @Test
  void everySentenceHasASpanishTranslationWithTheSamePlaceholders() throws IllegalAccessException {
    final MessageCatalog spanish = MessageCatalogs.forLocale(AppLocales.SPANISH);
    final List<String> problems = new ArrayList<>();
    for (final String english : sentences()) {
      final String translated = spanish.translate("email", english).orElse(null);
      if (translated == null) {
        problems.add("untranslated: " + english);
      } else if (!placeholders(english).equals(placeholders(translated))) {
        problems.add("placeholders differ: " + english);
      }
    }

    assertThat(problems).isEmpty();
  }

  private static List<String> sentences() throws IllegalAccessException {
    final List<String> found = new ArrayList<>();
    for (final Field field : EmailCopy.class.getDeclaredFields()) {
      final int modifiers = field.getModifiers();
      if (field.getType() == String.class
          && Modifier.isStatic(modifiers)
          && !Modifier.isPrivate(modifiers)) {
        found.add((String) field.get(null));
      }
    }
    return found;
  }

  private static List<String> placeholders(final String text) {
    final List<String> found = new ArrayList<>();
    final Matcher matcher = PLACEHOLDER.matcher(text);
    while (matcher.find()) {
      found.add(matcher.group());
    }
    return found.stream().sorted().toList();
  }

  // --- previews --------------------------------------------------------------------------------

  /**
   * Writes every email, in both languages, as an HTML and a text file into the directory named by
   * {@code -Demail.previews=<dir>}, so a designer can open them in a browser. Without the property
   * it only checks that the previews can be produced.
   */
  @Test
  void rendersPreviewsOfEveryEmailInBothLanguages() throws IOException {
    final String target = System.getProperty("email.previews");
    int written = 0;
    for (final Locale locale : AppLocales.SUPPORTED) {
      LocaleContextHolder.setLocale(locale);
      final List<Emails.Composed> emails = everyEmail();
      for (int index = 0; index < emails.size(); index++) {
        if (target != null) {
          final String name = String.format("%02d-%s", index + 1, locale.getLanguage());
          Files.createDirectories(Path.of(target));
          Files.writeString(
              Path.of(target, name + ".html"), emails.get(index).html(), StandardCharsets.UTF_8);
          Files.writeString(
              Path.of(target, name + ".txt"), emails.get(index).text(), StandardCharsets.UTF_8);
        }
        written++;
      }
    }

    assertThat(written).isEqualTo(AppLocales.SUPPORTED.size() * everyEmail().size());
  }

  // A tenant's people signed up with the consuming application, not with Clavaris: its emails are
  // sent in the Organization's name, in the header, the footer and the plain text.
  @Test
  void aTenantEmailIsSentInTheOrganizationsNameAndNeverClavaris() {
    assertThat(tenantEmails())
        .allSatisfy(
            email -> {
              assertThat(email.html()).contains(ORG).contains("Sent by " + ORG);
              assertThat(email.text()).startsWith("ACME ANALYTICS").contains("Sent by " + ORG);
              assertThat(email.html().toLowerCase(java.util.Locale.ROOT))
                  .doesNotContain("clavaris");
              assertThat(email.text().toLowerCase(java.util.Locale.ROOT))
                  .doesNotContain("clavaris");
              assertThat(email.subject()).doesNotContain("Clavaris");
            });
  }

  // Clavaris's own emails (to its platform accounts) stay as they are.
  @Test
  void aPlatformEmailIsStillSentInClavarisName() {
    assertThat(platformEmails())
        .allSatisfy(
            email -> {
              assertThat(email.html()).contains("Clavaris").contains("Sent by Clavaris");
              assertThat(email.text()).startsWith("CLAVARIS").contains("Sent by Clavaris");
            });
  }

  // An Organization whose name could not be found gets an email that names nobody, never Clavaris.
  @Test
  void aTenantEmailWithNoOrganizationNameNamesNobodyAndNeverFallsBackToClavaris() {
    final Emails.Composed email = Emails.verifyEmailLink(null, LINK, false);

    assertThat(email.html().toLowerCase(java.util.Locale.ROOT)).doesNotContain("clavaris");
    assertThat(email.text().toLowerCase(java.util.Locale.ROOT)).doesNotContain("clavaris");
    assertThat(email.html()).doesNotContain("Sent by").contains("This is an automated message");
    assertThat(email.text()).doesNotContain("Sent by").contains("This is an automated message");
    assertThat(email.text()).startsWith(email.subject());
  }

  @Test
  void aBlankOrganizationNameCountsAsNone() {
    assertThat(Emails.signInCode("   ", "111222").text()).doesNotContain("Sent by");
  }

  // The Organization's name is typed by whoever created it: it is escaped like any other value.
  @Test
  void anOrganizationNameIsEscapedInTheHtml() {
    final Emails.Composed email = Emails.signInCode("<script>alert(1)</script>", "111222");

    assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;");
  }

  @Test
  void theBrandedFooterIsTranslatedKeepingTheName() {
    LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));

    final Emails.Composed email = Emails.signInCode(ORG, "111222");

    assertThat(email.text()).contains("Enviado por " + ORG + ".");
  }
}
