package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0020 Decision 3, BR-ID-12: v1 is operator-managed only, same posture {@code
 * SetRateLimitPolicyForOrganizationCommand} already establishes — this is reachable exclusively via
 * the platform-tier management API, never a tenant's own token. {@code enabled = false} always
 * results in email/password remaining the only sign-in method, regardless of {@code providers} —
 * there is no code path anywhere that lets this command disable email/password itself.
 *
 * @param providers raw provider-name strings (e.g. {@code "GOOGLE"}) — validated against {@link
 *     SetSocialLoginPolicyForOrganizationService}'s own small local allowlist, a deliberate plain-
 *     string mirror of identity-module's real {@code SocialProvider} enum values, not a reference
 *     to that type itself (this module has no dependency on identity-module's own types at all,
 *     same rule {@code Organization.ownerPlatformAccountId} already follows for its own
 *     cross-module value).
 */
public record SetSocialLoginPolicyForOrganizationCommand(
    UUID organizationId, boolean enabled, List<String> providers, AuditActor actor) {}
