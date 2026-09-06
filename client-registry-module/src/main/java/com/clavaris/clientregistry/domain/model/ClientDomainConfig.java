package com.clavaris.clientregistry.domain.model;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0009 §2: an {@code OAuthClient}'s custom-domain registration and DNS TXT-record ownership
 * challenge — the mandatory prerequisite (BR-CLIENT-04) for embedding a production client's hosted
 * login in an iframe (ADR-0009 §1). Own table, one row per {@code OAuthClient}, same idiom {@link
 * RedirectPolicy}/{@link ClientBranding} already establish for a client-scoped, optional-by-default
 * aggregate — <b>except</b> absence of a row means "{@code SHARED} mode" (Clavaris's own default
 * host, dev-only for embedding), a real, valid, distinct state of its own, not merely "not yet
 * configured."
 *
 * <p>Requesting a domain (a fresh {@code mode}/{@code hostname} pair) always mints a brand-new
 * {@code dnsTxtChallengeToken} and resets {@code verificationStatus} to {@code PENDING} — even a
 * previously {@code VERIFIED} domain must re-prove ownership if its hostname or mode changes,
 * closing the takeover window a stale token would otherwise leave open.
 *
 * <p>ADR-0009 §4: {@code embeddingOrigin} is the consumer's own frontend origin allowed to embed
 * this client's hosted login in an iframe ({@code display=modal}) — deliberately not derived from
 * {@code OAuthClient.redirectUris} (those are OAuth2 callback URLs, not necessarily the top-level
 * page hosting the iframe) and independent of the DNS ownership challenge above: changing it never
 * resets {@code verificationStatus} (see {@link #withEmbeddingOrigin}).
 */
// LongVariable: verificationStatus/dnsTxtChallengeToken name exactly what ADR-0009 §2 itself
// calls the field, same RedirectPolicy/ClientBranding precedent. AvoidFieldNameMatchingMethodName:
// every field's accessor is named identically to the field itself, same idiom this module's every
// other value object already uses.
@SuppressWarnings({
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.LongVariable",
  "PMD.AvoidFieldNameMatchingMethodName"
})
public final class ClientDomainConfig {

  // RFC 1035-shaped hostname validation itself lives in HostnameValidator, not here — see that
  // class's own Javadoc for why (both the domain reason and the java:S5852/PMD.GodClass history).

  private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
  private static final int TOKEN_BYTE_LENGTH = 24;

  private final UUID id;
  private final UUID oauthClientId;
  private final ClientDomainMode mode;
  private final String hostname;
  private final DomainVerificationStatus verificationStatus;
  private final String dnsTxtChallengeToken;
  private final String embeddingOrigin;
  private final Instant verifiedAt;
  private final Instant createdAt;
  private final Instant updatedAt;

  // java:S107: ten persisted fields is what full rehydration of this row genuinely looks like,
  // same reconstitute/ClientDomainConfigEntity/OAuthClientEntity precedent for a wide, flat
  // aggregate — every other factory method funnels into this one canonical constructor rather
  // than each duplicating field assignment/validation itself. No PMD.ExcessiveParameterList
  // suppression here (unlike those siblings): PMD's own default threshold is 10, so it doesn't
  // flag exactly 10 params — only SonarCloud's stricter default (7) does.
  @SuppressWarnings("java:S107")
  private ClientDomainConfig(
      final UUID id,
      final UUID oauthClientId,
      final ClientDomainMode mode,
      final String hostname,
      final DomainVerificationStatus verificationStatus,
      final String dnsTxtChallengeToken,
      final String embeddingOrigin,
      final Instant verifiedAt,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.oauthClientId = Objects.requireNonNull(oauthClientId, "oauthClientId must not be null");
    this.mode = mode;
    this.hostname = validateHostnameIfPresent(hostname);
    this.verificationStatus = verificationStatus;
    this.dnsTxtChallengeToken = dnsTxtChallengeToken;
    this.embeddingOrigin = validateEmbeddingOriginIfPresent(embeddingOrigin);
    this.verifiedAt = verifiedAt;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /**
   * The implicit answer for an {@code OAuthClient} that has never requested a custom domain —
   * {@code SHARED} mode, every domain-specific field absent. Never persisted on its own.
   */
  public static ClientDomainConfig unconfigured(final UUID oauthClientId) {
    final Instant now = Instant.now();
    return new ClientDomainConfig(
        UUID.randomUUID(), oauthClientId, null, null, null, null, null, null, now, now);
  }

  /**
   * A brand-new domain request — mints a fresh challenge token and starts {@code PENDING},
   * regardless of whether this {@code OAuthClient} had a previous (possibly {@code VERIFIED} or
   * {@code FAILED}) domain configured; {@link #reRequest} below is what a real change replaces.
   * {@code embeddingOrigin} is independent of the DNS ownership challenge (ADR-0009 §4) — it may be
   * {@code null} for a client that only wants a branded standalone page, no iframe embedding.
   */
  public static ClientDomainConfig request(
      final UUID oauthClientId,
      final ClientDomainMode mode,
      final String hostname,
      final String embeddingOrigin) {
    final Instant now = Instant.now();
    return new ClientDomainConfig(
        UUID.randomUUID(),
        oauthClientId,
        Objects.requireNonNull(mode, "mode must not be null"),
        requireValidHostname(hostname),
        DomainVerificationStatus.PENDING,
        generateChallengeToken(),
        embeddingOrigin,
        null,
        now,
        now);
  }

  /**
   * A real row already exists for this {@code OAuthClient} — an operator re-pointing the domain
   * (new hostname and/or mode). Keeps the original {@code id}/{@code createdAt}, mints a fresh
   * challenge token, and resets to {@code PENDING} — same "changed config must re-prove ownership"
   * rule {@link #request} already establishes, just updating in place instead of a second row.
   */
  public ClientDomainConfig reRequest(
      final ClientDomainMode newMode, final String newHostname, final String newEmbeddingOrigin) {
    return new ClientDomainConfig(
        id,
        oauthClientId,
        Objects.requireNonNull(newMode, "mode must not be null"),
        requireValidHostname(newHostname),
        DomainVerificationStatus.PENDING,
        generateChallengeToken(),
        newEmbeddingOrigin,
        null,
        createdAt,
        Instant.now());
  }

  /**
   * Changes only {@code embeddingOrigin}, leaving {@code hostname}/{@code mode}/{@code
   * verificationStatus}/{@code dnsTxtChallengeToken} untouched — unlike {@link #reRequest}, this
   * never needs to re-prove DNS ownership: embeddingOrigin says who may embed the already-verified
   * domain, not which domain is being claimed.
   */
  public ClientDomainConfig withEmbeddingOrigin(final String newEmbeddingOrigin) {
    return new ClientDomainConfig(
        id,
        oauthClientId,
        mode,
        hostname,
        verificationStatus,
        dnsTxtChallengeToken,
        newEmbeddingOrigin,
        verifiedAt,
        createdAt,
        Instant.now());
  }

  /**
   * A DNS TXT lookup found the expected challenge token — this domain is now embedding-eligible.
   */
  public ClientDomainConfig markVerified() {
    return new ClientDomainConfig(
        id,
        oauthClientId,
        mode,
        hostname,
        DomainVerificationStatus.VERIFIED,
        dnsTxtChallengeToken,
        embeddingOrigin,
        Instant.now(),
        createdAt,
        Instant.now());
  }

  /**
   * A DNS TXT lookup did not find the expected challenge token — a normal, retryable operational
   * outcome (DNS propagation delay, a typo in the published record), never an exception: the
   * operator keeps the same hostname/mode/token and can simply retry verification later.
   */
  public ClientDomainConfig markFailed() {
    return new ClientDomainConfig(
        id,
        oauthClientId,
        mode,
        hostname,
        DomainVerificationStatus.FAILED,
        dnsTxtChallengeToken,
        embeddingOrigin,
        verifiedAt,
        createdAt,
        Instant.now());
  }

  // java:S107/PMD.ExcessiveParameterList: ten persisted fields is what full rehydration of this
  // row genuinely looks like, same ClientDomainConfigEntity/OAuthClientEntity precedent for a
  // wide, flat aggregate.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static ClientDomainConfig reconstitute(
      final UUID id,
      final UUID oauthClientId,
      final ClientDomainMode mode,
      final String hostname,
      final DomainVerificationStatus verificationStatus,
      final String dnsTxtChallengeToken,
      final String embeddingOrigin,
      final Instant verifiedAt,
      final Instant createdAt,
      final Instant updatedAt) {
    return new ClientDomainConfig(
        id,
        oauthClientId,
        mode,
        hostname,
        verificationStatus,
        dnsTxtChallengeToken,
        embeddingOrigin,
        verifiedAt,
        createdAt,
        updatedAt);
  }

  private static String generateChallengeToken() {
    final byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
    TOKEN_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String requireValidHostname(final String hostname) {
    if (hostname == null || hostname.isBlank()) {
      throw new IllegalArgumentException("hostname must not be blank");
    }
    return validateHostnameIfPresent(hostname);
  }

  // Two exits (null passes through unchecked for the unconfigured/SHARED state, a real value is
  // validated) is clearer here than forcing a single-return shape onto "absent" vs. "present" —
  // same rationale RedirectPolicy's own validateIfPresent suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String validateHostnameIfPresent(final String hostname) {
    if (hostname == null) {
      return null;
    }
    if (!HostnameValidator.isValid(hostname)) {
      throw new IllegalArgumentException("hostname must be a valid DNS hostname: " + hostname);
    }
    return hostname;
  }

  // Two exits (null passes through unchecked — no embedding, standalone-only — a real value is
  // validated) — same rationale validateHostnameIfPresent's own suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String validateEmbeddingOriginIfPresent(final String embeddingOrigin) {
    if (embeddingOrigin == null) {
      return null;
    }
    // Origin only — scheme+host+port, no path/query/fragment (RFC 6454): this value becomes a CSP
    // frame-ancestors source, where anything beyond an origin is meaningless and, for a path
    // component in particular, silently ignored by browsers rather than rejected — validating it
    // away here is cheaper than an operator discovering that the hard way.
    final URI parsed;
    try {
      parsed = URI.create(embeddingOrigin);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "embeddingOrigin must be a well-formed URI: " + embeddingOrigin, e);
    }
    if (!parsed.isAbsolute() || !"https".equalsIgnoreCase(parsed.getScheme())) {
      throw new IllegalArgumentException(
          "embeddingOrigin must be an absolute https origin: " + embeddingOrigin);
    }
    if (hasPathQueryOrFragment(parsed)) {
      throw new IllegalArgumentException(
          "embeddingOrigin must be an origin only, no path/query/fragment: " + embeddingOrigin);
    }
    return embeddingOrigin;
  }

  private static boolean hasPathQueryOrFragment(final URI parsed) {
    return (parsed.getPath() != null && !parsed.getPath().isEmpty())
        || parsed.getQuery() != null
        || parsed.getFragment() != null;
  }

  public UUID id() {
    return id;
  }

  public UUID oauthClientId() {
    return oauthClientId;
  }

  public Optional<ClientDomainMode> mode() {
    return Optional.ofNullable(mode);
  }

  public Optional<String> hostname() {
    return Optional.ofNullable(hostname);
  }

  public Optional<DomainVerificationStatus> verificationStatus() {
    return Optional.ofNullable(verificationStatus);
  }

  public Optional<String> dnsTxtChallengeToken() {
    return Optional.ofNullable(dnsTxtChallengeToken);
  }

  public Optional<String> embeddingOrigin() {
    return Optional.ofNullable(embeddingOrigin);
  }

  public Optional<Instant> verifiedAt() {
    return Optional.ofNullable(verifiedAt);
  }

  /** BR-CLIENT-04: the single check every embedding-eligibility decision ultimately reduces to. */
  public boolean isVerified() {
    return verificationStatus == DomainVerificationStatus.VERIFIED;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
