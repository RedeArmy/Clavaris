package com.clavaris.app.infrastructure.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a process whose security-critical secrets still hold the development default
 * committed in {@code application.yml}.
 *
 * <p>Those defaults exist so a developer can run the app with zero setup, and this repository is
 * public: anyone can read them. A deployment that keeps one of them has an AES-256-GCM key, an HMAC
 * secret or a keystore password that is "protected" by a value everybody already knows. {@code
 * docker-compose.prod.yml} already makes every one of these variables mandatory, but that guards
 * only that one launcher — {@code java -jar}, a Kubernetes manifest or a copied compose file would
 * run with the public keys and say nothing. The check therefore lives in the application, where
 * every launcher passes through it.
 *
 * <p><b>Policy.</b> Relaxed only under the {@code dev} or {@code test} profile, and never when
 * {@code prod} is active. A process with no profile at all is enforced, not exempted: "forgot to
 * set the profile" is exactly how a bare {@code java -jar} ends up in production.
 *
 * <p><b>Detection.</b> A setting is flagged when its resolved value equals the default written in
 * its own {@code ${VAR:default}} placeholder in {@code application.yml}. The default is read from
 * the configuration itself rather than copied into this class, so it cannot drift from the file,
 * and a value an operator supplies through any other source never matches it. Values are never
 * printed, only the setting and the variable that overrides it.
 *
 * <p><b>Timing.</b> A listener on {@link ApplicationEnvironmentPreparedEvent}, registered in {@code
 * META-INF/spring.factories}, so it fails the start before any bean exists — before the signing
 * keystore is created under the public password, and before a connection to the database is made.
 * It must run after configuration files are loaded, hence {@link Ordered#LOWEST_PRECEDENCE}.
 *
 * <p>Settings whose development default is not a secret of ours (the placeholder Google/GitHub
 * client secrets, the placeholder Supabase keys) are deliberately not listed; {@code
 * DevDefaultSecretsStartupGuardTest} fails when a new secret-looking default is added to {@code
 * application.yml} without being either listed here or explicitly exempted there.
 */
// PMD.AtLeastOneConstructor: instantiated reflectively by SpringFactoriesLoader and holds no state,
// only the policy below — same rationale WorkspaceAwareOidcUserInfoMapper's own identical
// suppression documents.
@SuppressWarnings("PMD.AtLeastOneConstructor")
public final class DevDefaultSecretsStartupGuard
    implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

  /**
   * Settings protecting data at rest or the integrity of stored credentials. Each has a public
   * default in {@code application.yml}.
   */
  /* package */ static final List<String> PROTECTED_KEYS =
      List.of(
          "clavaris.signing-key-store.password",
          "clavaris.webhook.secret-encryption-key",
          "clavaris.organization.social-credential-encryption-key",
          "clavaris.oauth2.token-hash-secret",
          "clavaris.rate-limit.key-hash-secret",
          "clavaris.idempotency.key-hash-secret",
          "spring.datasource.password");

  private static final String PROD_PROFILE = "prod";
  private static final Set<String> RELAXED_PROFILES = Set.of("dev", "test");

  // ${VARIABLE:default} — group 1 the variable an operator sets, group 2 the committed default.
  private static final Pattern PLACEHOLDER =
      Pattern.compile("^\\$\\{([^:}]+):(.+)}$", Pattern.DOTALL);

  @Override
  public void onApplicationEvent(final ApplicationEnvironmentPreparedEvent event) {
    enforce(event.getEnvironment());
  }

  @Override
  public int getOrder() {
    return LOWEST_PRECEDENCE;
  }

  /**
   * Throws when the environment is not a development/test one and any protected setting still holds
   * its committed default.
   */
  /* package */ static void enforce(final ConfigurableEnvironment environment) {
    if (isRelaxed(environment)) {
      return;
    }
    final List<DevDefault> found = findDevDefaults(environment);
    if (!found.isEmpty()) {
      throw new IllegalStateException(describe(found, environment));
    }
  }

  /* package */ static boolean isRelaxed(final Environment environment) {
    final List<String> active = Arrays.asList(environment.getActiveProfiles());
    return !active.contains(PROD_PROFILE) && active.stream().anyMatch(RELAXED_PROFILES::contains);
  }

  /** Protected settings whose resolved value is the default committed in their placeholder. */
  /* package */ static List<DevDefault> findDevDefaults(final ConfigurableEnvironment environment) {
    final List<DevDefault> found = new ArrayList<>();
    for (final String key : PROTECTED_KEYS) {
      final String resolved = environment.getProperty(key);
      committedDefault(environment, key)
          .filter(committed -> committed.value().equals(resolved))
          .ifPresent(committed -> found.add(new DevDefault(key, committed.variable())));
    }
    return found;
  }

  private static Optional<CommittedDefault> committedDefault(
      final ConfigurableEnvironment environment, final String key) {
    return StreamSupport.stream(environment.getPropertySources().spliterator(), false)
        .map(source -> source.getProperty(key))
        .filter(String.class::isInstance)
        .map(raw -> PLACEHOLDER.matcher((String) raw))
        .filter(Matcher::matches)
        .findFirst()
        .map(matcher -> new CommittedDefault(matcher.group(1), matcher.group(2)));
  }

  private static String describe(
      final List<DevDefault> found, final ConfigurableEnvironment environment) {
    final StringBuilder message =
        new StringBuilder(
            "Refusing to start: the settings below still hold the development default committed"
                + " in application.yml. This repository is public, so anything they protect"
                + " (encrypted webhook and social-login secrets, stored token hashes, the signing"
                + " keystore, the database) is protected by a value anyone can read.\n");
    for (final DevDefault entry : found) {
      message
          .append("  - ")
          .append(entry.key())
          .append("  (set ")
          .append(entry.variable())
          .append(")\n");
    }
    message
        .append("Give each a real secret (for a 256-bit key: openssl rand -base64 32). To run on a")
        .append(
            " developer machine, activate the dev profile instead (SPRING_PROFILES_ACTIVE=dev).")
        .append(" Active profiles: ")
        .append(Arrays.toString(environment.getActiveProfiles()))
        .append('.');
    return message.toString();
  }

  /** A protected setting that holds its committed default, and the variable that overrides it. */
  /* package */ record DevDefault(String key, String variable) {}

  private record CommittedDefault(String variable, String value) {}
}
