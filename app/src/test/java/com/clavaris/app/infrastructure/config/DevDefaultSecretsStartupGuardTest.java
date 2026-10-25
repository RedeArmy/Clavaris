package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class DevDefaultSecretsStartupGuardTest {

  // Stands in for application.yml: each protected setting is a ${VARIABLE:default} placeholder.
  private static final Map<String, String> PLACEHOLDERS =
      Map.of(
          "clavaris.signing-key-store.password", "${KEYSTORE_PASSWORD:public-keystore-default}",
          "clavaris.webhook.secret-encryption-key", "${WEBHOOK_KEY:public-webhook-default}",
          "clavaris.organization.social-credential-encryption-key",
              "${SOCIAL_KEY:public-social-default}",
          "clavaris.oauth2.token-hash-secret", "${TOKEN_HASH:public-token-default}",
          "clavaris.rate-limit.key-hash-secret", "${RATE_HASH:public-rate-default}",
          "clavaris.idempotency.key-hash-secret", "${IDEMPOTENCY_HASH:public-idem-default}",
          "spring.datasource.password", "${DB_PASSWORD:public-db-default}");

  private static final List<String> PUBLIC_DEFAULTS =
      List.of(
          "public-keystore-default",
          "public-webhook-default",
          "public-social-default",
          "public-token-default",
          "public-rate-default",
          "public-idem-default",
          "public-db-default");

  private static MockEnvironment environmentWithCommittedDefaults(final String... profiles) {
    final MockEnvironment environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addLast(new MapPropertySource("application.yml", new LinkedHashMap<>(PLACEHOLDERS)));
    environment.setActiveProfiles(profiles);
    return environment;
  }

  // Real secrets reach the process as environment variables, a source ahead of application.yml.
  private static void supplyRealSecret(final MockEnvironment environment, final String variable) {
    environment.setProperty(variable, "a-real-secret-" + variable);
  }

  @Test
  void refusesToStartWithNoProfileWhenSecretsKeepTheirPublicDefault() {
    final MockEnvironment environment = environmentWithCommittedDefaults();

    assertThatThrownBy(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Refusing to start")
        .hasMessageContaining("clavaris.webhook.secret-encryption-key")
        .hasMessageContaining("WEBHOOK_KEY")
        .hasMessageContaining("clavaris.signing-key-store.password")
        .hasMessageContaining("spring.datasource.password");
  }

  @Test
  void neverPrintsTheSecretValues() {
    final MockEnvironment environment = environmentWithCommittedDefaults("prod");

    assertThatThrownBy(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .satisfies(
            failure ->
                PUBLIC_DEFAULTS.forEach(
                    value -> assertThat(failure.getMessage()).doesNotContain(value)));
  }

  @Test
  void refusesUnderTheProdProfile() {
    final MockEnvironment environment = environmentWithCommittedDefaults("prod");

    assertThatThrownBy(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anUnrecognisedProfileIsStillEnforced() {
    final MockEnvironment environment = environmentWithCommittedDefaults("staging");

    assertThatThrownBy(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void prodWinsWhenDevIsActivatedAlongsideIt() {
    final MockEnvironment environment = environmentWithCommittedDefaults("prod", "dev");

    assertThatThrownBy(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void allowsTheDevProfile() {
    final MockEnvironment environment = environmentWithCommittedDefaults("dev");

    assertThatCode(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsTheTestProfile() {
    final MockEnvironment environment = environmentWithCommittedDefaults("test");

    assertThatCode(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void startsWhenEverySecretIsSuppliedFromAnotherSource() {
    final MockEnvironment environment = environmentWithCommittedDefaults("prod");
    List.of(
            "KEYSTORE_PASSWORD",
            "WEBHOOK_KEY",
            "SOCIAL_KEY",
            "TOKEN_HASH",
            "RATE_HASH",
            "IDEMPOTENCY_HASH",
            "DB_PASSWORD")
        .forEach(variable -> supplyRealSecret(environment, variable));

    assertThatCode(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .doesNotThrowAnyException();
  }

  @Test
  void namesOnlyTheSettingsThatKeepTheirDefault() {
    final MockEnvironment environment = environmentWithCommittedDefaults("prod");
    List.of(
            "KEYSTORE_PASSWORD",
            "SOCIAL_KEY",
            "TOKEN_HASH",
            "RATE_HASH",
            "IDEMPOTENCY_HASH",
            "DB_PASSWORD")
        .forEach(variable -> supplyRealSecret(environment, variable));

    assertThatThrownBy(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .hasMessageContaining("clavaris.webhook.secret-encryption-key")
        .hasMessageNotContaining("clavaris.signing-key-store.password")
        .hasMessageNotContaining("spring.datasource.password");
  }

  @Test
  void anOperatorSettingTheVariableToThePublicDefaultIsStillRefused() {
    final MockEnvironment environment = environmentWithCommittedDefaults("prod");
    List.of(
            "KEYSTORE_PASSWORD",
            "SOCIAL_KEY",
            "TOKEN_HASH",
            "RATE_HASH",
            "IDEMPOTENCY_HASH",
            "DB_PASSWORD")
        .forEach(variable -> supplyRealSecret(environment, variable));
    environment.setProperty("WEBHOOK_KEY", "public-webhook-default");

    assertThatThrownBy(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .hasMessageContaining("WEBHOOK_KEY");
  }

  // ---- against the real application.yml, so a rename or a new default cannot slip past the list
  // ----

  private static MockEnvironment environmentFromRealApplicationYml(final String... profiles)
      throws IOException {
    final MockEnvironment environment = new MockEnvironment();
    final List<PropertySource<?>> loaded =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
    loaded.forEach(source -> environment.getPropertySources().addLast(source));
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  void flagsEveryProtectedSettingInTheRealApplicationYmlWhenNothingOverridesThem()
      throws IOException {
    final MockEnvironment environment = environmentFromRealApplicationYml();

    final List<String> flagged =
        DevDefaultSecretsStartupGuard.findDevDefaults(environment).stream()
            .map(DevDefaultSecretsStartupGuard.DevDefault::key)
            .toList();

    assertThat(flagged)
        .as(
            "every protected key needs a ${VAR:default} placeholder in application.yml — a rename"
                + " there would otherwise silently stop the guard protecting it")
        .containsExactlyInAnyOrderElementsOf(DevDefaultSecretsStartupGuard.PROTECTED_KEYS);
  }

  @Test
  void theRealApplicationYmlStartsUnderTheDevProfile() throws IOException {
    final MockEnvironment environment = environmentFromRealApplicationYml("dev");

    assertThatCode(() -> DevDefaultSecretsStartupGuard.enforce(environment))
        .doesNotThrowAnyException();
  }

  // Third parties' credentials that application.yml fills with an obvious placeholder. A
  // placeholder
  // guards nothing of ours and is valid nowhere, and docker-compose.prod.yml already insists on a
  // non-blank value, so refusing it would only force operators to invent a different dummy.
  private static final Map<String, String> EXEMPT_PLACEHOLDERS =
      Map.of(
          "spring.security.oauth2.client.registration.google.client-secret",
          "placeholder Google OAuth client secret; per-Organization credentials are the real ones",
          "spring.security.oauth2.client.registration.github.client-secret",
          "placeholder GitHub OAuth client secret; per-Organization credentials are the real ones",
          "clavaris.profile-picture.supabase.access-key-id",
          "placeholder for an external storage account that dev does not use",
          "clavaris.profile-picture.supabase.secret-access-key",
          "placeholder for an external storage account that dev does not use");

  private static final Pattern SECRET_LOOKING_NAME =
      Pattern.compile("(secret|password|key|token|credential)", Pattern.CASE_INSENSITIVE);

  @Test
  void noOtherSecretLookingSettingKeepsAnUnreviewedPublicDefault() throws IOException {
    final MockEnvironment environment = environmentFromRealApplicationYml();
    final Pattern placeholderWithDefault = Pattern.compile("^\\$\\{[^:}]+:(.+)}$");

    final List<String> unreviewed = new java.util.ArrayList<>();
    for (final PropertySource<?> source : environment.getPropertySources()) {
      if (source instanceof final EnumerablePropertySource<?> enumerable) {
        for (final String name : enumerable.getPropertyNames()) {
          final String lastSegment = name.substring(name.lastIndexOf('.') + 1);
          final Matcher matcher =
              placeholderWithDefault.matcher(String.valueOf(enumerable.getProperty(name)));
          if (SECRET_LOOKING_NAME.matcher(lastSegment).find()
              && matcher.matches()
              && !DevDefaultSecretsStartupGuard.PROTECTED_KEYS.contains(name)
              && !EXEMPT_PLACEHOLDERS.containsKey(name)) {
            unreviewed.add(name);
          }
        }
      }
    }

    assertThat(unreviewed)
        .as(
            "a secret-looking setting with a public default in application.yml must be added to"
                + " DevDefaultSecretsStartupGuard.PROTECTED_KEYS, or listed in EXEMPT_PLACEHOLDERS"
                + " here with the reason it guards nothing of ours")
        .isEmpty();
  }
}
