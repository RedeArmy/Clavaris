package com.clavaris.webhook.domain.model;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0007 §1/§2: a consumer's registered "push events here" URL, scoped to exactly one
 * Organization (ADR-0010) — one Organization may register several endpoints (e.g. a production and
 * a staging URL), each with its own independent signing secret and event-type subscription. {@code
 * organizationId} is a raw {@link UUID}, not identity-module's own {@code OrganizationId} value
 * type, same module-independence reason {@code OAuthClient}'s own field stays primitive-typed — the
 * hexagonal dependency rule applied at the module-graph level.
 *
 * <p>The signing secret itself is stored already-encrypted ({@code currentSecretEncrypted}) —
 * unlike {@code OAuthClient.clientSecretHash} (a one-way hash, only ever used to verify an inbound
 * credential), this secret must be recoverable in cleartext at delivery time to compute an outbound
 * HMAC signature, so a one-way hash cannot be the storage shape here. Encryption/decryption is an
 * infrastructure concern (see {@code WebhookSigningSecretCipher}) — this class only ever holds and
 * moves the already-encrypted string, the same "hashing happens at the port boundary" discipline
 * {@code OAuthClient}'s own Javadoc establishes for its own secret.
 *
 * <p><b>Secret rotation (ADR-0007's own first open question, resolved):</b> {@link #rotateSecret}
 * keeps the previous secret alongside the new one, valid until {@code previousSecretExpiresAt} —
 * the dispatcher signs every delivery with both while the overlap window is open (Stripe's own
 * "multiple signatures during rotation" pattern), giving the endpoint owner time to switch their
 * own verification code over before the old secret stops being honoured. Same "current + previous
 * with bounded overlap" shape as {@code SigningKey} rotation in identity-module, applied to a
 * symmetric secret instead of an asymmetric key pair.
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@code OAuthClient} suppresses them — the deliberate record-style accessor
 * convention used throughout this codebase's value objects. TooManyMethods is the same shape of
 * false positive: ten one-line accessors plus factories/mutators is what a value object with this
 * many fields looks like, not a sign this class does too much. LongVariable: every one of these
 * field names is the exact, spec-shaped term for what it holds, not arbitrarily long — same
 * precedent {@code OAuthClient}'s own suppression already establishes. Unlike {@code
 * WebhookDelivery}, this class's own {@code rotateSecret}/{@code deactivate}/{@code activate}/
 * {@code subscribesTo}/{@code activeSecretsEncrypted} methods carry enough real behaviour that
 * PMD's own DataClass metric doesn't flag it — no suppression needed for that one.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.LongVariable"
})
public final class WebhookEndpoint {

  private final UUID id;
  private final UUID organizationId;
  private final String url;
  private final String description;
  private final List<String> subscribedEventTypes;
  private final String currentSecretEncrypted;
  private final String previousSecretEncrypted;
  private final Instant previousSecretExpiresAt;
  private final boolean active;
  private final Instant createdAt;

  @SuppressWarnings("java:S107") // one parameter per persisted field — same rationale as
  // OAuthClient's own identical suppression: a synthetic parameter object here would add
  // indirection without removing any real complexity.
  private WebhookEndpoint(
      final UUID id,
      final UUID organizationId,
      final String url,
      final String description,
      final List<String> subscribedEventTypes,
      final String currentSecretEncrypted,
      final String previousSecretEncrypted,
      final Instant previousSecretExpiresAt,
      final boolean active,
      final Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.url = requireValidUrl(url);
    this.description = description;
    this.subscribedEventTypes = requireNonEmptyEventTypes(subscribedEventTypes);
    this.currentSecretEncrypted = requireNonBlank(currentSecretEncrypted, "currentSecretEncrypted");
    this.previousSecretEncrypted = previousSecretEncrypted;
    this.previousSecretExpiresAt = previousSecretExpiresAt;
    this.active = active;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  /**
   * @param currentSecretEncrypted the already-encrypted secret — this factory never sees or accepts
   *     a raw secret; encryption happens at the port boundary ({@code
   *     RegisterWebhookEndpointService}), same discipline as {@code OAuthClient.register}.
   */
  public static WebhookEndpoint register(
      final UUID organizationId,
      final String url,
      final String description,
      final List<String> subscribedEventTypes,
      final String currentSecretEncrypted) {
    return new WebhookEndpoint(
        UUID.randomUUID(),
        organizationId,
        url,
        description,
        subscribedEventTypes,
        currentSecretEncrypted,
        null,
        null,
        true,
        Instant.now());
  }

  /** Rehydrates an existing row — preserves the real persisted {@code id}/{@code createdAt}. */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static WebhookEndpoint reconstitute(
      final UUID id,
      final UUID organizationId,
      final String url,
      final String description,
      final List<String> subscribedEventTypes,
      final String currentSecretEncrypted,
      final String previousSecretEncrypted,
      final Instant previousSecretExpiresAt,
      final boolean active,
      final Instant createdAt) {
    return new WebhookEndpoint(
        id,
        organizationId,
        url,
        description,
        subscribedEventTypes,
        currentSecretEncrypted,
        previousSecretEncrypted,
        previousSecretExpiresAt,
        active,
        createdAt);
  }

  /**
   * @param newSecretEncrypted the already-encrypted new secret — see this class's own Javadoc.
   * @param overlapWindow how long the outgoing secret keeps being honoured alongside the new one —
   *     an operational value ({@code RotateWebhookEndpointSecretService}'s own {@code @Value}), not
   *     a domain constant, same reasoning ADR-0010 §6.2's rate-limit ceiling already establishes.
   */
  public WebhookEndpoint rotateSecret(
      final String newSecretEncrypted, final Duration overlapWindow) {
    return new WebhookEndpoint(
        id,
        organizationId,
        url,
        description,
        subscribedEventTypes,
        newSecretEncrypted,
        currentSecretEncrypted,
        Instant.now().plus(overlapWindow),
        active,
        createdAt);
  }

  public WebhookEndpoint deactivate() {
    return new WebhookEndpoint(
        id,
        organizationId,
        url,
        description,
        subscribedEventTypes,
        currentSecretEncrypted,
        previousSecretEncrypted,
        previousSecretExpiresAt,
        false,
        createdAt);
  }

  public WebhookEndpoint activate() {
    return new WebhookEndpoint(
        id,
        organizationId,
        url,
        description,
        subscribedEventTypes,
        currentSecretEncrypted,
        previousSecretEncrypted,
        previousSecretExpiresAt,
        true,
        createdAt);
  }

  /** BR-WEBHOOK-06: only event types this endpoint actually subscribed to are ever delivered. */
  public boolean subscribesTo(final String eventType) {
    return subscribedEventTypes.contains(eventType);
  }

  /**
   * Every still-valid encrypted secret to sign a delivery with — one entry normally, two during a
   * rotation's overlap window (see this class's own Javadoc). Order matters to callers that build
   * the {@code Clavaris-Signature} header: current first.
   */
  // Two exits (rotation overlap still open vs. not) is clearer here than forcing a single-return
  // shape onto two genuinely different cases — same rationale as RegisterOAuthClientController's
  // own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  public List<String> activeSecretsEncrypted(final Instant now) {
    if (previousSecretEncrypted != null
        && previousSecretExpiresAt != null
        && now.isBefore(previousSecretExpiresAt)) {
      return List.of(currentSecretEncrypted, previousSecretEncrypted);
    }
    return List.of(currentSecretEncrypted);
  }

  private static String requireNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  // BR-WEBHOOK-07: https only. Signing a payload proves it came from Clavaris and wasn't altered
  // in transit, but says nothing about confidentiality — a plain-http delivery would still expose
  // the payload (and, structurally, everything an attacker needs to forge a valid signature isn't
  // in the payload, but the payload itself can carry ids/roles worth keeping off the wire in the
  // clear) to any network observer. Same "meaningless without TLS" reasoning redirect_uris would
  // have if BR-CLIENT-01 allowed a bare http:// entry.
  private static String requireValidUrl(final String url) {
    requireNonBlank(url, "url");
    final URI parsed;
    try {
      parsed = URI.create(url);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("url must be a well-formed URI: " + url, e);
    }
    if (!parsed.isAbsolute()) {
      throw new IllegalArgumentException("url must be an absolute URI: " + url);
    }
    if (!"https".equalsIgnoreCase(parsed.getScheme())) {
      throw new IllegalArgumentException("url must use https: " + url);
    }
    return url;
  }

  private static List<String> requireNonEmptyEventTypes(final List<String> eventTypes) {
    if (eventTypes == null || eventTypes.isEmpty()) {
      throw new IllegalArgumentException("subscribedEventTypes must not be empty");
    }
    for (final String eventType : eventTypes) {
      if (eventType == null || eventType.isBlank()) {
        throw new IllegalArgumentException("subscribedEventTypes must not contain a blank entry");
      }
    }
    return List.copyOf(eventTypes);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public String url() {
    return url;
  }

  public String description() {
    return description;
  }

  public List<String> subscribedEventTypes() {
    return subscribedEventTypes;
  }

  public String currentSecretEncrypted() {
    return currentSecretEncrypted;
  }

  public String previousSecretEncrypted() {
    return previousSecretEncrypted;
  }

  public Instant previousSecretExpiresAt() {
    return previousSecretExpiresAt;
  }

  public boolean active() {
    return active;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
