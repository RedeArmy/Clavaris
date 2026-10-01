package com.clavaris.identity.infrastructure.adapter.out.breachcheck;

import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.common.infrastructure.adapter.out.resilience.CircuitBreakerMetricsBinder;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordChecker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements {@link BreachedPasswordChecker} against HIBP's own Pwned Passwords k-anonymity range
 * API — only the first 5 hex characters of the password's SHA-1 hash ever leave this server
 * (BR-ID-07: "the full password never leaves the server, only a truncated hash prefix"). SHA-1 here
 * is correct, not a mistake: this is the exact algorithm the Pwned Passwords corpus itself is
 * indexed by — it is never used as this system's own credential-storage hash (that remains
 * Argon2id, BR-ID-01/ADR-0005, entirely unrelated to this class).
 *
 * <p>Same "this class builds its own HTTP-mechanics collaborator, which is never itself a Spring
 * bean" shape {@code ResendMailSender}/{@code ResendHttpClient} already establish — see this
 * class's own constructor.
 *
 * <p><b>Fail-open, per {@link BreachedPasswordChecker}'s own contract:</b> every failure mode
 * {@link PwnedPasswordsHttpClient} can throw is caught here and turned into {@code false} — the one
 * place that contract is actually implemented, so every real call site stays free of its own
 * try/catch.
 */
// PMD.LongVariable: DEFAULT_RANGE_ENDPOINT/failureRateThreshold/waitDurationInOpenStateSeconds name
// exactly what they are — same precedent ResendMailSender's own identical class-level suppression
// already documents for this exact three-@Value CircuitBreaker-tuning shape.
@SuppressWarnings("PMD.LongVariable")
@Component
class PwnedPasswordsBreachedPasswordChecker implements BreachedPasswordChecker {

  private static final Logger LOG =
      LoggerFactory.getLogger(PwnedPasswordsBreachedPasswordChecker.class);

  private static final URI DEFAULT_RANGE_ENDPOINT = URI.create("https://api.pwnedpasswords.com/");
  private static final int PREFIX_LENGTH = 5;
  private static final String HASH_ALGORITHM = "SHA-1";

  private final PwnedPasswordsHttpClient httpClient;

  // Same three-@Value-tunable CircuitBreaker shape as ResendMailSender's own identical constructor
  // — see that class's own Javadoc for why these specific defaults (50%/10/30s).
  @SuppressWarnings("java:S107")
  @Autowired
  /* package */ PwnedPasswordsBreachedPasswordChecker(
      @Value("${clavaris.resilience.pwned-passwords.failure-rate-threshold:50}")
          final float failureRateThreshold,
      @Value("${clavaris.resilience.pwned-passwords.sliding-window-size:10}")
          final int slidingWindowSize,
      @Value("${clavaris.resilience.pwned-passwords.wait-duration-in-open-state-seconds:30}")
          final long waitDurationInOpenStateSeconds,
      final SecurityMetricsRecorder metrics) {
    this(
        new PwnedPasswordsHttpClient(
            HttpClient.newHttpClient(),
            DEFAULT_RANGE_ENDPOINT,
            buildCircuitBreaker(
                failureRateThreshold, slidingWindowSize, waitDurationInOpenStateSeconds, metrics)));
  }

  // Test-only: lets PwnedPasswordsBreachedPasswordCheckerTest inject a fully-controlled
  // PwnedPasswordsHttpClient (mocked or pointed at a local stub) — same shape ResendMailSender's
  // own test-only constructor already establishes. Never invoked by Spring; @Autowired on the
  // constructor above resolves the ambiguity unambiguously.
  /* package */ PwnedPasswordsBreachedPasswordChecker(final PwnedPasswordsHttpClient httpClient) {
    this.httpClient = httpClient;
  }

  // PMD.OnlyOneReturn: two genuinely distinct outcomes (the real lookup result vs. the fail-open
  // "treat as not breached" path) — same "each outcome needs its own exit" rationale as every
  // other identically-suppressed method in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public boolean isBreached(final String rawPassword) {
    final String hash = sha1Hex(rawPassword);
    final String prefix = hash.substring(0, PREFIX_LENGTH);
    final String suffix = hash.substring(PREFIX_LENGTH);

    try {
      return httpClient.lookupRange(prefix).stream().anyMatch(line -> matchesSuffix(line, suffix));
    } catch (final PwnedPasswordsLookupException e) {
      // BR-ID-07's own confirmed fail-open contract — see this class's own Javadoc and
      // BreachedPasswordChecker's own Javadoc for why: Clavaris's own availability for every
      // registration/password-change must never depend on this third party's uptime.
      LOG.warn("event=breached_password_check_failed", e);
      return false;
    }
  }

  @SuppressWarnings("PMD.OnlyOneReturn") // an absent separator vs. a real suffix comparison are
  // two genuinely distinct outcomes, same rationale as isBreached's own identical suppression.
  private static boolean matchesSuffix(final String responseLine, final String suffix) {
    final int separator = responseLine.indexOf(':');
    if (separator < 0) {
      return false;
    }
    return separator == suffix.length()
        && responseLine.regionMatches(true, 0, suffix, 0, separator);
  }

  private static String sha1Hex(final String rawPassword) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
      return HexFormat.of()
          .withUpperCase()
          .formatHex(digest.digest(rawPassword.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      // SHA-1 is a JDK-mandated algorithm (every conforming JVM provides it) — unreachable in
      // practice, same "this is a programming/environment error, fail loudly" stance as every
      // other NoSuchAlgorithmException catch in this codebase.
      throw new IllegalStateException("SHA-1 MessageDigest unavailable", e);
    }
  }

  private static CircuitBreaker buildCircuitBreaker(
      final float failureRateThreshold,
      final int slidingWindowSize,
      final long waitDurationInOpenStateSeconds,
      final SecurityMetricsRecorder metrics) {
    final CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "pwned-passwords",
            CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
                .build());
    CircuitBreakerMetricsBinder.bind(circuitBreaker, metrics);
    return circuitBreaker;
  }
}
