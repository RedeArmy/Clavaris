package com.clavaris.app.infrastructure.config;

import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimitIdentifiers;
import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimitRule;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * The {@link RateLimitRule}s of the admin API chain (TD-ARCH-042): pure configuration data, split
 * out of {@link AdminApiSecurityConfig} the way {@code OrganizationRateLimitRules} was for the
 * tenant chain. Same rules, same order, same parameters.
 */
final class AdminApiRateLimitRules {

  private static final String ADMIN_API_PATH_PATTERN = "/api/v1/admin/**";

  private AdminApiRateLimitRules() {}

  // Data, not logic — one parameter per tunable limit. PMD.LongVariable: each name matches the
  // @Value property it carries at the call site.
  @SuppressWarnings("PMD.LongVariable")
  /* package */ static List<RateLimitRule> all(
      final int adminApiPerClientLimit,
      final int accountsDeletePerClientLimit,
      final int organizationsDeletePerClientLimit,
      final int workspaceMembersWritePerClientLimit,
      final int accountsImpersonatePerClientLimit) {
    return List.of(
        new RateLimitRule(
            "admin-api-post:client",
            HttpMethod.POST,
            ADMIN_API_PATH_PATTERN,
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedPlatformClientId,
            adminApiPerClientLimit,
            Duration.ofMinutes(1)),
        new RateLimitRule(
            "admin-api-put:client",
            HttpMethod.PUT,
            ADMIN_API_PATH_PATTERN,
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedPlatformClientId,
            adminApiPerClientLimit,
            Duration.ofMinutes(1)),
        // TD-SEC-062: every write verb on this chain had blanket + endpoint-specific
        // rate-limit coverage except DELETE — a real, previously-unguarded gap (e.g.
        // DELETE /api/v1/admin/organizations/*/social-credentials/*), same ceiling as
        // the two blanket rules above.
        new RateLimitRule(
            "admin-api-delete:client",
            HttpMethod.DELETE,
            ADMIN_API_PATH_PATTERN,
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedPlatformClientId,
            adminApiPerClientLimit,
            Duration.ofMinutes(1)),
        // BR-DATA-02: individual accounts — a routine call for a consuming
        // application's own backend (e.g. per-user deletion requests), so this ceiling
        // stays well above the blanket rule above rather than redundantly tighter.
        new RateLimitRule(
            "admin-api-accounts-delete:client",
            HttpMethod.POST,
            "/api/v1/admin/accounts/*:delete",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedPlatformClientId,
            accountsDeletePerClientLimit,
            Duration.ofMinutes(5)),
        // BR-DATA-02/03: an entire Organization's whole account pool — the single most
        // irreversible call this surface exposes (this file's own scope-check comment
        // above), so this is the tightest ceiling on this whole chain, deliberately.
        new RateLimitRule(
            "admin-api-organizations-delete:client",
            HttpMethod.POST,
            "/api/v1/admin/organizations/*:delete",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedPlatformClientId,
            organizationsDeletePerClientLimit,
            Duration.ofMinutes(5)),
        // BR-WS-04: a real Account + a real outbound email per call — its own tighter
        // ceiling than the blanket admin-api-post:client rule above, same
        // "side-effect-bearing endpoint" precedent as the two :delete rules.
        new RateLimitRule(
            "admin-api-workspace-members-write:client",
            HttpMethod.POST,
            "/api/v1/admin/workspaces/*/members",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedPlatformClientId,
            workspaceMembersWritePerClientLimit,
            Duration.ofMinutes(5)),
        // SDE-III feature build, 2026-09-03 (Impersonation): tighter than every other
        // rule here bar the two :delete endpoints — see this method's own parameter
        // comment for why.
        new RateLimitRule(
            "admin-api-accounts-impersonate:client",
            HttpMethod.POST,
            "/api/v1/admin/accounts/*:impersonate",
            RateLimitRule.always(),
            RateLimitIdentifiers::authenticatedPlatformClientId,
            accountsImpersonatePerClientLimit,
            Duration.ofMinutes(5)));
  }
}
