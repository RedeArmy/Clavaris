package com.clavaris.clientregistry.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0010, BR-ORG-02: a consuming application's protocol registration, scoped to exactly one
 * {@code Organization} — {@code organizationId} is an opaque {@link UUID}, not identity-module's
 * own {@code OrganizationId} value type, for the same module-independence reason {@code
 * SigningKeyProvisioner} stays primitive-typed (the hexagonal dependency rule applied at the
 * module-graph level). One Organization may register several {@code OAuthClient}s (web + mobile for
 * the same system), all sharing that Organization's isolated account pool.
 *
 * <p>Deliberately a separate class from {@code PlatformClient} — belongs to exactly one
 * Organization, never none (data-model.md §2).
 *
 * <p>Immutable: every change returns a new instance. What a client may register is validated by
 * {@link ClientRegistrationRules}, not here, so this class holds state and transitions only.
 */
// Only the record-style accessor idiom is suppressed (coding-standards.md §3a): a field and its
// accessor share a name (AvoidFieldNameMatchingMethodName), as does {@code id()} (ShortMethodName/
// ShortVariable), and twelve one-line accessors plus the real transitions exceed TooManyMethods.
// None of this hides a design smell: GodClass, LongVariable and CyclomaticComplexity are no longer
// suppressed here — the validation moved out and the long names were shortened instead.
@SuppressWarnings({"PMD.TooManyMethods"})
public final class OAuthClient {

  private final UUID id;
  private final UUID organizationId;
  private final String clientId;
  private final String clientSecretHash;
  private final List<String> redirectUris;
  private final List<String> allowedGrantTypes;
  private final List<String> allowedScopes;
  private final boolean requireConsent;
  private final List<String> postLogoutUris;
  private final Instant createdAt;
  private final boolean active;

  // SDE-III review, 2026-09-15: the row's own optimistic-lock version, read back unchanged by
  // reconstitute and carried forward unchanged by deactivate()/rotateSecret() — see
  // ConcurrentClientModificationException's own Javadoc for the lost-update race this closes.
  // Never set by register() to anything but 0 (a brand-new row), never mutated in memory — the
  // actual increment happens at the DB layer, via the JPA entity's own @Version field.
  private final int version;

  // One parameter per persisted column — same rationale as this class's own TooManyMethods
  // suppression above: a rehydration factory for a 12-column aggregate takes 12 parameters, not a
  // sign this constructor does too much. Introducing a synthetic parameter-object purely to dodge
  // the threshold would add indirection without removing any real complexity.
  @SuppressWarnings("java:S107")
  private OAuthClient(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> redirectUris,
      final List<String> allowedGrantTypes,
      final List<String> allowedScopes,
      final boolean requireConsent,
      final List<String> postLogoutUris,
      final Instant createdAt,
      final boolean active,
      final int version) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.clientId = ClientCredentialFields.requireNonBlank(clientId, "clientId");
    this.clientSecretHash =
        ClientCredentialFields.requireNonBlank(clientSecretHash, "clientSecretHash");
    // BR-ORG-06 (SDE-III refactor, 2026-09-23): an Organization's default OAuthClient is now
    // auto-provisioned synchronously at Organization-creation time, before the owning
    // PlatformAccount has typed any redirect URI at all — requiring >=1 here would make that
    // provisioning impossible. Empty is the genuine "not configured yet" state, same as
    // postLogoutRedirectUris already allowed; BR-CLIENT-01's exact-match guarantee simply has
    // nothing to match against until the user adds one, so /authorize requests for this client
    // fail closed (no registered redirect URI to match) rather than being rejected at
    // registration time.
    this.redirectUris = ClientRegistrationRules.requireValidUris(redirectUris, "redirectUris");
    this.allowedGrantTypes =
        ClientRegistrationRules.requireKnownGrantTypes(
            ClientRegistrationRules.requireNonEmpty(allowedGrantTypes, "allowedGrantTypes"));
    this.allowedScopes = ClientRegistrationRules.requireValidScopes(allowedScopes);
    this.requireConsent = requireConsent;
    // TD-FUT-018: genuinely optional, same as redirectUris now is (see that field's own comment
    // above) — a client with no post-logout redirect configured simply gets SAS's own bare
    // default (redirect to {clavarisBaseUrl}/). Still validated the same way (well-formed,
    // absolute) when present: RFC-shaped junk here would be un-matchable at RP-Initiated Logout
    // time either way, same requireValidUris rule redirectUris uses.
    this.postLogoutUris =
        ClientRegistrationRules.requireValidUris(postLogoutUris, "postLogoutRedirectUris");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.active = active;
    this.version = version;
  }

  /**
   * @param clientSecretHash the already-hashed value — this factory never sees or accepts a raw
   *     secret; hashing happens at the port boundary, same discipline as {@code PlatformClient}.
   * @param requireConsent TD-SEC-026: whether SAS must show the end user a consent screen before
   *     issuing an authorization code to this client. No implicit default at this layer — the web
   *     adapter resolves an absent request field to {@code true} (secure by default, ADR-0017); the
   *     domain factory itself takes an explicit value so a future caller can't add one without
   *     consciously deciding it.
   */
  @SuppressWarnings("java:S107")
  public static OAuthClient register(
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> redirectUris,
      final List<String> allowedGrantTypes,
      final List<String> allowedScopes,
      final boolean requireConsent,
      final List<String> postLogoutUris) {
    return new OAuthClient(
        UUID.randomUUID(),
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        Instant.now(),
        true,
        0);
  }

  /**
   * Rehydrates an existing row — preserves the real persisted {@code id}, same discipline as {@code
   * PlatformClient#reconstitute}. One parameter per persisted column, same rationale as the private
   * constructor's own identical suppression above — a synthetic parameter object here would add
   * indirection without removing any real complexity.
   *
   * @param version SDE-III review, 2026-09-15: the row's real persisted optimistic-lock version —
   *     see this class's own {@code version} field Javadoc.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static OAuthClient reconstitute(
      final UUID id,
      final UUID organizationId,
      final String clientId,
      final String clientSecretHash,
      final List<String> redirectUris,
      final List<String> allowedGrantTypes,
      final List<String> allowedScopes,
      final boolean requireConsent,
      final List<String> postLogoutUris,
      final Instant createdAt,
      final boolean active,
      final int version) {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        createdAt,
        active,
        version);
  }

  /**
   * SDE-III review, 2026-09-11: added alongside the dashboard's own deactivate action — same
   * rationale as {@code OrganizationClient#deactivate}/{@code PlatformClient#deactivate}. Revoking
   * this client's own already-issued tokens is a separate, existing concern ({@code
   * OrganizationRegisteredClientRepository}'s own lookup path), not this method's job —
   * deactivating only stops *new* authorization/token requests for this client from succeeding.
   */
  public OAuthClient deactivate() {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        createdAt,
        false,
        version);
  }

  /**
   * Live UX request, 2026-09-24: re-enables a previously deactivated client — same "the client
   * itself decides whether it's usable, not a separate flag elsewhere" rationale as {@link
   * #deactivate()}. Reuses the exact same {@code clientId}/{@code clientSecretHash}/redirect
   * settings — nothing about this client's identity or configuration is regenerated by a
   * deactivate→activate cycle, only the {@code active} flag itself changes.
   */
  public OAuthClient activate() {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        createdAt,
        true,
        version);
  }

  /**
   * SDE-III refactor, 2026-09-23 (auto-provisioned default client) — {@code redirectUris}/{@code
   * postLogoutRedirectUris} are the only two fields a consuming Organization may change after
   * creation; {@code allowedGrantTypes}/{@code allowedScopes}/{@code requireConsent} stay
   * creation-time-only, fixed by {@code OAuthClientDefaults}. Bundled into one mutator, not two —
   * the dashboard edits both in a single "Redirect settings" form, and there is no scenario where
   * changing one without the other is a real, distinct operation worth its own use case.
   */
  public OAuthClient updateRedirectSettings(
      final List<String> redirectUris, final List<String> postLogoutUris) {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        ClientRegistrationRules.requireValidUris(redirectUris, "redirectUris"),
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        ClientRegistrationRules.requireValidUris(postLogoutUris, "postLogoutRedirectUris"),
        createdAt,
        active,
        version);
  }

  /**
   * SDE-III correction, 2026-09-24 (live UX request): reverses BR-ORG-06's original
   * "creation-time-only, fixed by OAuthClientDefaults" rule for {@code allowedGrantTypes} — an
   * Organization owner may now change it from the dashboard's own "Configuration" card, gated
   * behind a checkbox list of {@link OAuthGrantTypeCatalog#KNOWN} (never free text), same
   * validation this constructor already runs against every other construction path.
   */
  public OAuthClient updateGrantTypes(final List<String> allowedGrantTypes) {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        createdAt,
        active,
        version);
  }

  /** Same rationale as {@link #updateGrantTypes(List)}, for {@code allowedScopes}. */
  public OAuthClient updateScopes(final List<String> allowedScopes) {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        createdAt,
        active,
        version);
  }

  /** Same rationale as {@link #updateGrantTypes(List)}, for {@code requireConsent}. */
  public OAuthClient updateConsent(final boolean requireConsent) {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        clientSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        createdAt,
        active,
        version);
  }

  /**
   * Same rationale as {@code OrganizationClient#rotateSecret}/{@code PlatformClient#rotateSecret}.
   */
  public OAuthClient rotateSecret(final String newSecretHash) {
    return new OAuthClient(
        id,
        organizationId,
        clientId,
        newSecretHash,
        redirectUris,
        allowedGrantTypes,
        allowedScopes,
        requireConsent,
        postLogoutUris,
        createdAt,
        active,
        version);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public String clientId() {
    return clientId;
  }

  public String clientSecretHash() {
    return clientSecretHash;
  }

  public List<String> redirectUris() {
    return redirectUris;
  }

  public List<String> allowedGrantTypes() {
    return allowedGrantTypes;
  }

  public List<String> allowedScopes() {
    return allowedScopes;
  }

  public boolean requireConsent() {
    return requireConsent;
  }

  public List<String> postLogoutRedirectUris() {
    return postLogoutUris;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public boolean active() {
    return active;
  }

  public int version() {
    return version;
  }
}
