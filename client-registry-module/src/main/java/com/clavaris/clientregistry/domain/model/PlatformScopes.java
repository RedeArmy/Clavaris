package com.clavaris.clientregistry.domain.model;

import java.util.List;

/**
 * ADR-0010: platform-tier scopes are namespaced {@code platform:*}, reserved and structurally
 * distinct from any per-Organization management scope — prevents a scope-string collision between
 * the two tiers. Scopes are added here as more platform-tier use cases are built, not pre-declared
 * ahead of them.
 *
 * <p>Class-wide suppressions, not per-field: {@code PMD.LongVariable} — these exact names are the
 * public scope strings other tokens/config compare against (AdminApiSecurityConfig, .env-seeded
 * bootstrap scopes), where a shortened identifier would only make call sites harder to read; a
 * per-field suppression on every one of them tripped {@code PMD.AvoidDuplicateLiterals} on the
 * suppression string itself. {@code PMD.DataClass} — this class is deliberately nothing but a
 * namespaced constants holder plus the one derived list, not an organically grown class that should
 * gain behavior.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.DataClass"})
public final class PlatformScopes {

  public static final String ORGANIZATIONS_WRITE = "platform:organizations:write";

  /** ADR-0010 §6.2: tuning an Organization's rate-limit capacity ceiling — operator-only in v1. */
  public static final String RATE_LIMIT_POLICY_WRITE = "platform:rate-limit-policy:write";

  /**
   * TD-SEC-008/ADR-0010 §5.2: manually-triggered signing-key rotation — operator-only, same
   * defence-in-depth posture as every other admin-API scope here (a platform token that can create
   * Organizations doesn't automatically get to also rotate one's signing key).
   */
  public static final String SIGNING_KEYS_ROTATE = "platform:signing-keys:rotate";

  /**
   * TD-SEC-029: the emergency, zero-overlap purge for a confirmed compromise
   * (`incident-response-signing-key-compromise.md` §3.6) — its own scope, deliberately separate
   * from {@link #SIGNING_KEYS_ROTATE}, same defence-in-depth reasoning {@link
   * #WORKSPACE_MEMBERS_REMOVE} already establishes relative to {@link #WORKSPACE_MEMBERS_WRITE} (a
   * token that can rotate a key on a routine schedule doesn't automatically get to also force an
   * immediate, breaking, zero-overlap eviction).
   */
  public static final String SIGNING_KEYS_PURGE = "platform:signing-keys:purge";

  /**
   * TD-SEC-018: rotating another {@code PlatformClient}'s secret — the real, code-driven
   * replacement for raw SQL against production named in {@code
   * incident-response-platform-client-compromise.md} §3a.
   */
  public static final String PLATFORM_CLIENTS_ROTATE_SECRET =
      "platform:platform-clients:rotate-secret";

  /** TD-SEC-018: revoking a {@code PlatformClient} — the self-service compromise-recovery path. */
  public static final String PLATFORM_CLIENTS_REVOKE = "platform:platform-clients:revoke";

  /**
   * BR-DATA-02: hard-deleting an {@code Account} — a real, permanent, irreversible operation, its
   * own dedicated scope for the same defence-in-depth reasoning as every other admin-API rule here
   * (a platform token that can create Organizations doesn't automatically get to also delete an end
   * user's identity).
   */
  public static final String ACCOUNTS_DELETE = "platform:accounts:delete";

  /**
   * BR-DATA-02: hard-deleting an entire {@code Organization} and its whole owned account pool — the
   * single most destructive operation this management API exposes, its own dedicated scope for the
   * same defence-in-depth reasoning as every other admin-API rule here, deliberately separate from
   * {@link #ACCOUNTS_DELETE} (one identity vs. an entire tenant).
   */
  public static final String ORGANIZATIONS_DELETE = "platform:organizations:delete";

  /**
   * BR-WS: creating a {@code Workspace} within an Organization — its own scope, same
   * defence-in-depth reasoning as every other admin-API rule here.
   */
  public static final String WORKSPACES_WRITE = "platform:workspaces:write";

  /**
   * BR-WS-04/05: adding a member (provisions a real {@code Account}) or changing an existing
   * member's role — grouped under one scope, same risk tier (both mutate workspace membership,
   * neither is independently destructive the way removal is).
   */
  public static final String WORKSPACE_MEMBERS_WRITE = "platform:workspace-members:write";

  /**
   * BR-WS-03: removing a member — its own scope, deliberately separate from {@link
   * #WORKSPACE_MEMBERS_WRITE}, same defence-in-depth reasoning {@link #ACCOUNTS_DELETE} already
   * establishes relative to the other admin-API scopes (a token that can add/promote members
   * doesn't automatically get to also remove one).
   */
  public static final String WORKSPACE_MEMBERS_REMOVE = "platform:workspace-members:remove";

  /**
   * Reversible ban/unban ({@code SuspendAccountController}/{@code ReactivateAccountController}) —
   * one shared scope for both directions, same "grouped under one scope for same-risk-tier actions"
   * precedent {@link #WORKSPACE_MEMBERS_WRITE} already establishes: suspend and reactivate are
   * symmetric, reversible, much lower stakes than {@link #ACCOUNTS_DELETE}'s permanent hard delete.
   */
  public static final String ACCOUNTS_SUSPEND = "platform:accounts:suspend";

  /**
   * ADR-0020 Decision 3, BR-ID-12: turning per-Organization social login on/off and choosing its
   * allowed providers — operator-managed only in v1, its own scope for the same defence-in-depth
   * reasoning as every other admin-API rule here. Structurally cannot touch email/password
   * availability at all (that's never gated by anything this scope reaches).
   */
  public static final String SOCIAL_LOGIN_POLICY_WRITE = "platform:social-login-policy:write";

  /**
   * ADR-0007: registering/deactivating a {@code WebhookEndpoint} or rotating its signing secret —
   * grouped under one scope, same "same risk tier" precedent as {@link #WORKSPACE_MEMBERS_WRITE}
   * (all three mutate one endpoint's own configuration; none reaches another Organization's data).
   */
  public static final String WEBHOOK_ENDPOINTS_WRITE = "platform:webhook-endpoints:write";

  /**
   * ADR-0007: manually replaying one {@code WebhookDelivery} — its own scope, deliberately separate
   * from {@link #WEBHOOK_ENDPOINTS_WRITE}, same defence-in-depth reasoning {@link
   * #WORKSPACE_MEMBERS_REMOVE} already establishes (a token that can register/rotate an endpoint
   * doesn't automatically get to also re-trigger delivery of an arbitrary past event to it).
   */
  public static final String WEBHOOK_DELIVERIES_REPLAY = "platform:webhook-deliveries:replay";

  /**
   * Granted to the bootstrap {@code PlatformClient} (BR-PLATFORM-03) — the operator's own client,
   * gets everything that exists so far.
   */
  public static final List<String> BOOTSTRAP_DEFAULT =
      List.of(
          ORGANIZATIONS_WRITE,
          RATE_LIMIT_POLICY_WRITE,
          SIGNING_KEYS_ROTATE,
          SIGNING_KEYS_PURGE,
          PLATFORM_CLIENTS_ROTATE_SECRET,
          PLATFORM_CLIENTS_REVOKE,
          ACCOUNTS_DELETE,
          ORGANIZATIONS_DELETE,
          WORKSPACES_WRITE,
          WORKSPACE_MEMBERS_WRITE,
          WORKSPACE_MEMBERS_REMOVE,
          ACCOUNTS_SUSPEND,
          SOCIAL_LOGIN_POLICY_WRITE,
          WEBHOOK_ENDPOINTS_WRITE,
          WEBHOOK_DELIVERIES_REPLAY);

  private PlatformScopes() {}
}
