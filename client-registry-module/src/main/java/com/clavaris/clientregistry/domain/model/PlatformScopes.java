package com.clavaris.clientregistry.domain.model;

import java.util.List;

/**
 * ADR-0010: platform-tier scopes are namespaced {@code platform:*}, reserved and structurally
 * distinct from any per-Organization management scope — prevents a scope-string collision between
 * the two tiers. Only the one scope CreateOrganization needs exists so far; more are added here as
 * more platform-tier use cases are built, not pre-declared ahead of them.
 */
public final class PlatformScopes {

  // PMD's LongVariable rule doesn't exempt SCREAMING_SNAKE_CASE constants by default — kept
  // spelled out in full rather than abbreviated, since this exact string is the public scope
  // name other tokens/config compare against (AdminApiSecurityConfig, .env-seeded bootstrap
  // scopes), where a shortened identifier would only make call sites harder to read.
  @SuppressWarnings("PMD.LongVariable")
  public static final String ORGANIZATIONS_WRITE = "platform:organizations:write";

  /**
   * Granted to the bootstrap {@code PlatformClient} (BR-PLATFORM-03) — the operator's own client,
   * gets everything that exists so far.
   */
  public static final List<String> BOOTSTRAP_DEFAULT = List.of(ORGANIZATIONS_WRITE);

  private PlatformScopes() {}
}
