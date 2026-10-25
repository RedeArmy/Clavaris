import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load test for the interactive authorization-code + PKCE sign-in, the one flow the Apache Bench
 * scenarios in {@code run.sh} cannot express (it needs cookies, a CSRF token and four chained
 * requests). Each simulated sign-in is a fresh browser: authorize, sign in with a password, come
 * back to authorize for a code, then exchange the code with the client secret and the PKCE
 * verifier. Every stage is timed on its own, because the two expensive steps cost different things:
 * the password sign-in is one Argon2id verification of a human password (ADR-0005, deliberately
 * slow, unavoidable) and the token exchange is the client-secret check.
 *
 * <p>Run it as a single source file, no build needed ({@code java PkceLoginLoadTest.java}); see
 * {@code README.md} in this directory for the setup it expects and the rate-limit settings to raise
 * on the target instance first (otherwise the limiter, not the server, is what is measured).
 *
 * <p>Environment: {@code CLAVARIS_BASE_URL}, {@code ORG_ID}, {@code CLIENT_ID}, {@code
 * CLIENT_SECRET}, {@code REDIRECT_URI}, {@code USER_EMAIL_PREFIX} and {@code USER_PASSWORD} (the
 * accounts {@code <prefix>1..<prefix>N@example.test}, created by {@code setup-pkce-fixture.sh}),
 * {@code ACCOUNTS} (how many exist), {@code LEVELS} (default {@code 1,3,10,30}).
 */
public final class PkceLoginLoadTest {

  private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
  private static final Pattern SET_COOKIE_NAME_VALUE = Pattern.compile("^([^=;\\s]+)=([^;]*)");
  private static final String[] STAGES = {"authorize", "loginPage", "login", "code", "token"};

  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();
  private final SecureRandom random = new SecureRandom();
  private final String base;
  private final String orgId;
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;
  private final String emailPrefix;
  private final String password;
  private final int accounts;
  private final AtomicInteger nextAccount = new AtomicInteger();

  private PkceLoginLoadTest() {
    base = env("CLAVARIS_BASE_URL", "http://localhost:8080");
    orgId = require("ORG_ID");
    clientId = require("CLIENT_ID");
    clientSecret = require("CLIENT_SECRET");
    redirectUri = env("REDIRECT_URI", "https://client.example.test/callback");
    emailPrefix = env("USER_EMAIL_PREFIX", "load+clavaris_test");
    password = require("USER_PASSWORD");
    accounts = Integer.parseInt(env("ACCOUNTS", "30"));
  }

  public static void main(final String[] args) throws Exception {
    final PkceLoginLoadTest test = new PkceLoginLoadTest();
    final int[] levels =
        Arrays.stream(env("LEVELS", "1,3,10,30").split(",")).mapToInt(Integer::parseInt).toArray();
    // The first flows pay for class loading, JIT and connection setup on both sides; they say
    // nothing about steady state, so they are run and thrown away.
    final int warmup = Integer.parseInt(env("WARMUP_FLOWS", "20"));
    if (warmup > 0) {
      test.runLevelQuietly(3, warmup);
    }
    System.out.printf(
        "| Concurrency | Flows | OK | sign-in p95 | token p95 | whole flow p50 | p95 | p99 | flows/s | Failures |%n");
    System.out.printf("|---|---|---|---|---|---|---|---|---|---|%n");
    for (final int level : levels) {
      test.runLevel(level);
    }
  }

  private void runLevel(final int concurrency) throws Exception {
    final int flows = Math.max(30, concurrency * 10);
    final List<Outcome> outcomes = execute(concurrency, flows);
    report(concurrency, flows, outcomes, lastSeconds);
  }

  private void runLevelQuietly(final int concurrency, final int flows) throws Exception {
    execute(concurrency, flows);
  }

  private double lastSeconds;

  private List<Outcome> execute(final int concurrency, final int flows) throws Exception {
    final List<Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
    final Semaphore slots = new Semaphore(concurrency);
    final long started = System.nanoTime();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      final List<Future<?>> pending = new ArrayList<>();
      for (int i = 0; i < flows; i++) {
        slots.acquire();
        pending.add(
            pool.submit(
                () -> {
                  try {
                    outcomes.add(oneFlow());
                  } finally {
                    slots.release();
                  }
                }));
      }
      for (final Future<?> f : pending) {
        f.get();
      }
    }
    lastSeconds = (System.nanoTime() - started) / 1e9;
    return outcomes;
  }

  private void report(
      final int concurrency, final int flows, final List<Outcome> outcomes, final double seconds) {
    final List<Outcome> ok = outcomes.stream().filter(o -> o.failure == null).toList();
    final Map<String, Integer> failures = new LinkedHashMap<>();
    outcomes.stream()
        .filter(o -> o.failure != null)
        .forEach(o -> failures.merge(o.failure, 1, Integer::sum));
    System.out.printf(
        "| %d | %d | %d | %s | %s | %s | %s | %s | %.1f | %s |%n",
        concurrency,
        flows,
        ok.size(),
        ms(percentile(stage(ok, "login"), 95)),
        ms(percentile(stage(ok, "token"), 95)),
        ms(percentile(totals(ok), 50)),
        ms(percentile(totals(ok), 95)),
        ms(percentile(totals(ok), 99)),
        ok.size() / seconds,
        failures.isEmpty() ? "none" : failures.toString());
  }

  private Outcome oneFlow() {
    final Map<String, String> jar = new ConcurrentHashMap<>();
    final Map<String, Long> timings = new LinkedHashMap<>();
    final String email =
        emailPrefix + (1 + Math.floorMod(nextAccount.getAndIncrement(), accounts)) + "@example.test";
    try {
      final String verifier = randomUrlSafe(32);
      final String challenge = sha256Url(verifier);
      final String state = randomUrlSafe(8);

      // 1. Unauthenticated authorize: must bounce to this Organization's hosted login.
      HttpResponse<String> r =
          timed(timings, "authorize", get(jar, "/o/" + orgId + "/oauth2/authorize?" + authorizeQuery(challenge, state)));
      if (r.statusCode() != 302) {
        return Outcome.failed("authorize:" + r.statusCode());
      }

      // 2. The login page, for its CSRF token.
      r = timed(timings, "loginPage", get(jar, "/o/" + orgId + "/login?clientId=" + clientId));
      if (r.statusCode() != 200) {
        return Outcome.failed("loginPage:" + r.statusCode());
      }
      final Matcher csrf = CSRF.matcher(r.body());
      if (!csrf.find()) {
        return Outcome.failed("loginPage:no-csrf");
      }

      // 3. Password sign-in: one Argon2id verification of a human password.
      final String form =
          "_csrf=" + enc(csrf.group(1)) + "&email=" + enc(email) + "&password=" + enc(password);
      r = timed(timings, "login", post(jar, "/o/" + orgId + "/login", form, null));
      if (r.statusCode() != 302) {
        return Outcome.failed("login:" + r.statusCode());
      }
      final String backToAuthorize = location(r);

      // 4. Authorize again, now signed in: a code on the client's redirect URI.
      r = timed(timings, "code", getAbsolute(jar, backToAuthorize));
      final String code = r.statusCode() == 302 ? queryParam(location(r), "code") : null;
      if (code == null) {
        return Outcome.failed("code:" + r.statusCode());
      }

      // 5. Token exchange: client-secret check plus PKCE verifier.
      final String basic =
          Base64.getEncoder()
              .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
      final String tokenForm =
          "grant_type=authorization_code&code="
              + enc(code)
              + "&redirect_uri="
              + enc(redirectUri)
              + "&code_verifier="
              + verifier;
      r = timed(timings, "token", post(jar, "/o/" + orgId + "/oauth2/token", tokenForm, basic));
      if (r.statusCode() != 200) {
        return Outcome.failed("token:" + r.statusCode());
      }
      return new Outcome(null, timings);
    } catch (final Exception e) {
      return Outcome.failed("exception:" + e.getClass().getSimpleName());
    }
  }

  private String authorizeQuery(final String challenge, final String state) {
    return "response_type=code&client_id="
        + enc(clientId)
        + "&redirect_uri="
        + enc(redirectUri)
        + "&scope=openid&code_challenge="
        + challenge
        + "&code_challenge_method=S256&state="
        + state;
  }

  // ---- tiny HTTP + cookie plumbing: one jar per simulated browser ----

  private Request get(final Map<String, String> jar, final String path) {
    return new Request(jar, base + path, null, null);
  }

  private Request getAbsolute(final Map<String, String> jar, final String url) {
    return new Request(jar, url, null, null);
  }

  private Request post(
      final Map<String, String> jar, final String path, final String form, final String basic) {
    return new Request(jar, base + path, form, basic);
  }

  private record Request(Map<String, String> jar, String url, String form, String basic) {}

  private HttpResponse<String> timed(
      final Map<String, Long> timings, final String stage, final Request request)
      throws Exception {
    final long start = System.nanoTime();
    final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()));
    if (!request.jar().isEmpty()) {
      builder.header(
          "Cookie",
          String.join(
              "; ", request.jar().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
    }
    if (request.basic() != null) {
      builder.header("Authorization", "Basic " + request.basic());
    }
    if (request.form() != null) {
      builder
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(request.form()));
    } else {
      builder.GET();
    }
    final HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    timings.put(stage, System.nanoTime() - start);
    response
        .headers()
        .allValues("Set-Cookie")
        .forEach(
            header -> {
              final Matcher m = SET_COOKIE_NAME_VALUE.matcher(header);
              if (m.find()) {
                request.jar().put(m.group(1), m.group(2));
              }
            });
    return response;
  }

  private static String location(final HttpResponse<?> response) {
    return response.headers().firstValue("Location").orElse("");
  }

  private static String queryParam(final String url, final String name) {
    final int q = url.indexOf('?');
    if (q < 0) {
      return null;
    }
    for (final String pair : url.substring(q + 1).split("&")) {
      final int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  // ---- numbers ----

  private record Outcome(String failure, Map<String, Long> timings) {
    static Outcome failed(final String why) {
      return new Outcome(why, Map.of());
    }
  }

  private static List<Long> stage(final List<Outcome> ok, final String name) {
    return ok.stream().map(o -> o.timings.get(name)).toList();
  }

  private static List<Long> totals(final List<Outcome> ok) {
    return ok.stream()
        .map(o -> Arrays.stream(STAGES).mapToLong(s -> o.timings.get(s)).sum())
        .toList();
  }

  private static long percentile(final List<Long> values, final int p) {
    if (values.isEmpty()) {
      return -1;
    }
    final List<Long> sorted = values.stream().sorted().toList();
    final int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private static String ms(final long nanos) {
    return nanos < 0 ? "n/a" : String.format("%.0fms", nanos / 1e6);
  }

  // ---- helpers ----

  private String randomUrlSafe(final int bytes) {
    final byte[] b = new byte[bytes];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String sha256Url(final String value) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
  }

  private static String enc(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String env(final String name, final String fallback) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String require(final String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set (see load-testing/README.md)");
    }
    return value;
  }
}
