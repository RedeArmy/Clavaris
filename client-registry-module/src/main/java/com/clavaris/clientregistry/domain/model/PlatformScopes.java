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
   * Granted to the bootstrap {@code PlatformClient} (BR-PLATFORM-03) — the operator's own client,
   * gets everything that exists so far.
   */
  public static final List<String> BOOTSTRAP_DEFAULT =
      List.of(
          ORGANIZATIONS_WRITE,
          RATE_LIMIT_POLICY_WRITE,
          SIGNING_KEYS_ROTATE,
          PLATFORM_CLIENTS_ROTATE_SECRET,
          PLATFORM_CLIENTS_REVOKE,
          ACCOUNTS_DELETE,
          ORGANIZATIONS_DELETE);

  private PlatformScopes() {}
}
