package com.clavaris.webhook.domain.model;

import com.clavaris.common.domain.model.AbsoluteHttpsUrlValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0007 §1/§2: a consumer's registered "push events here" URL, scoped to exactly one
 * Organization (ADR-0010) — one Organization may register several endpoints, each with its own
 * signing secret and event-type subscription. {@code organizationId} is a raw {@link UUID}, not
 * identity-module's own value type, same module-independence reason {@code OAuthClient}'s own field
 * stays primitive-typed.
 *
 * <p>The signing secret is stored already-encrypted ({@code currentSecretEncrypted}), not hashed —
 * unlike {@code OAuthClient.clientSecretHash}, it must be recoverable in cleartext at delivery time
 * to compute an outbound HMAC signature. Encryption/decryption is an infrastructure concern ({@code
 * WebhookSigningSecretCipher}); this class only holds and moves the encrypted string.
 *
 * <p>{@link #rotateSecret} keeps the previous secret valid until {@code previousSecretExpiresAt} —
 * the dispatcher signs with both during the overlap window (Stripe's own rotation pattern), same
 * "current + previous with bounded overlap" shape as identity-module's {@code SigningKey}.
 *
 * <p>PMD suppressions below: coding-standards.md §3a.
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

  // BR-WEBHOOK-07: https only — a signature proves origin/integrity, not confidentiality; plain
  // http would still expose the payload to any network observer.
  //
  // TD-ARCH-019: only checks well-formedness/https here — the separate SSRF-adjacent
  // (private/loopback/cloud-metadata) address check is WebhookUrlSsrfChecker's own job
  // (TD-SEC-053), not this constructor's.
  private static String requireValidUrl(final String url) {
    requireNonBlank(url, "url");
    return AbsoluteHttpsUrlValidator.requireAbsoluteHttps(url, "url");
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
