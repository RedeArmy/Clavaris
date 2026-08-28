import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * TD-FUT-017: isolates the exact mechanism {@code load-testing/README.md}'s own {@code
 * /oauth2/token} run identified as the real concurrency ceiling — Argon2id verification itself,
 * not Tomcat/Postgres/Redis (already shown to have headroom by the same run's own JWKS baseline).
 * Measures {@link Argon2PasswordEncoder#matches} latency directly, at the same concurrency levels
 * (1/3/10/30) the original HTTP-level run used, for two parameter sets:
 *
 * <ul>
 *   <li><b>CURRENT</b> — {@code m=16384,t=2,p=1}, confirmed live (not assumed) by decoding a real
 *       encoded hash from this exact project's resolved {@code spring-security-crypto:7.1.1}: this
 *       is what {@code Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()} actually produces,
 *       which is what {@code Argon2PasswordHasher}/{@code Argon2PasswordVerifier} both use today.
 *   <li><b>LOWER_MEMORY</b> — {@code m=9216,t=4,p=1}, a real published OWASP Argon2id option
 *       (lower memory, more iterations — same total cost-factor tier, not a weakened hash). The
 *       hypothesis this benchmark tests: under concurrency, several verifications competing for
 *       the same limited CPU cache/memory bandwidth may degrade faster than they would running
 *       alone, so a smaller per-verification memory footprint could raise the concurrency ceiling
 *       even though each individual verification does more iterations.
 * </ul>
 *
 * <p>Deliberately outside JUnit/Surefire (no {@code Test} suffix — Surefire's default include
 * pattern never picks this up, matching {@code load-testing/run.sh}'s own "explicit tool, not part
 * of the regular test suite" posture) — this is a load-testing tool, not a correctness test, and
 * running it inside a shared CI/test JVM would contend with every other test for the same limited
 * cores this benchmark is deliberately trying to saturate.
 *
 * <p>Run: {@code javac -cp <classpath> Argon2ConcurrencyBenchmark.java && java -cp
 * <classpath>:. Argon2ConcurrencyBenchmark} — see {@code README.md}'s own "Argon2 parameter
 * comparison" section for the exact classpath and a captured run.
 */
public final class Argon2ConcurrencyBenchmark {

  private static final String PROBE_PASSWORD = "a-realistic-length-password-1234";
  private static final int[] CONCURRENCY_LEVELS = {1, 3, 10, 30};
  private static final int OPERATIONS_PER_LEVEL = 60;
  private static final int WARMUP_OPERATIONS = 10;

  private Argon2ConcurrencyBenchmark() {}

  public static void main(final String[] args) throws InterruptedException {
    final List<ParameterSet> parameterSets =
        List.of(
            // saltLength=16, hashLength=32 match Spring Security's own defaults for both — only
            // memory/iterations vary, so the comparison isolates that one dimension.
            new ParameterSet("CURRENT (m=16384,t=2,p=1)", 16, 32, 1, 16384, 2),
            new ParameterSet("LOWER_MEMORY (m=9216,t=4,p=1)", 16, 32, 1, 9216, 4));

    for (final ParameterSet params : parameterSets) {
      System.out.println();
      System.out.println("=== " + params.label() + " ===");
      final Argon2PasswordEncoder encoder =
          new Argon2PasswordEncoder(
              params.saltLength(),
              params.hashLength(),
              params.parallelism(),
              params.memory(),
              params.iterations());
      final String encodedHash = encoder.encode(PROBE_PASSWORD);

      warmUp(encoder, encodedHash);

      System.out.printf(
          "%-12s %8s %8s %8s %14s%n",
          "concurrency", "p50(ms)", "p95(ms)", "p99(ms)", "throughput(ops/s)");
      for (final int concurrency : CONCURRENCY_LEVELS) {
        runAtConcurrency(encoder, encodedHash, concurrency);
      }
    }
  }

  private static void warmUp(final Argon2PasswordEncoder encoder, final String encodedHash) {
    for (int i = 0; i < WARMUP_OPERATIONS; i++) {
      encoder.matches(PROBE_PASSWORD, encodedHash);
    }
  }

  private static void runAtConcurrency(
      final Argon2PasswordEncoder encoder, final String encodedHash, final int concurrency)
      throws InterruptedException {
    final ExecutorService pool = Executors.newFixedThreadPool(concurrency);
    final List<Callable<Long>> tasks =
        Arrays.asList(new Callable[OPERATIONS_PER_LEVEL]).stream()
            .map(
                _ ->
                    (Callable<Long>)
                        () -> {
                          final long start = System.nanoTime();
                          encoder.matches(PROBE_PASSWORD, encodedHash);
                          return System.nanoTime() - start;
                        })
            .toList();
    final long batchStart = System.nanoTime();
    final List<Future<Long>> futures;
    try {
      futures = pool.invokeAll(tasks);
    } finally {
      pool.shutdown();
      pool.awaitTermination(2, TimeUnit.MINUTES);
    }
    final long batchElapsedNanos = System.nanoTime() - batchStart;
    final long[] latenciesNanos = new long[futures.size()];
    for (int i = 0; i < futures.size(); i++) {
      latenciesNanos[i] = getUnchecked(futures.get(i));
    }
    Arrays.sort(latenciesNanos);
    final double throughputPerSecond =
        latenciesNanos.length / (batchElapsedNanos / 1_000_000_000.0);
    System.out.printf(
        "%-12d %8.1f %8.1f %8.1f %14.1f%n",
        concurrency,
        percentile(latenciesNanos, 0.50),
        percentile(latenciesNanos, 0.95),
        percentile(latenciesNanos, 0.99),
        throughputPerSecond);
  }

  // Only ever thrown by a task this class's own lambda above defines, which never itself throws
  // a checked exception — Callable's own signature is the only reason this exists at all.
  private static long getUnchecked(final Future<Long> future) {
    try {
      return future.get();
    } catch (final Exception e) {
      throw new IllegalStateException("Benchmark task failed", e);
    }
  }

  private static double percentile(final long[] sortedNanos, final double p) {
    final int index = (int) Math.ceil(p * sortedNanos.length) - 1;
    final int clampedIndex = Math.max(0, Math.min(sortedNanos.length - 1, index));
    return sortedNanos[clampedIndex] / 1_000_000.0;
  }

  private record ParameterSet(
      String label, int saltLength, int hashLength, int parallelism, int memory, int iterations) {}
}
