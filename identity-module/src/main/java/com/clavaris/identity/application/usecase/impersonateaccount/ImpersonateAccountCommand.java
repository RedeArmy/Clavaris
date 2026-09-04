package com.clavaris.identity.application.usecase.impersonateaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;

/**
 * SDE-III feature build, 2026-09-03: a support/operator action, always initiated by a {@code
 * PlatformClient} via {@code /api/v1/admin/**} — same tier as {@code SuspendAccountCommand}'s own
 * identical rationale, never the target {@code Account} itself, and never another tenant {@code
 * Account}.
 *
 * <p>Deliberately carries nothing about which {@code OAuthClient}/scopes the minted token will be
 * scoped to — that resolution is client-registry-module's own concern (a registered client, its
 * allowed scopes), which this module has no dependency on (§7.2, the module-independence rule this
 * whole codebase enforces). This use case's only job is validating the target {@code Account} and
 * recording that impersonation of it began; the {@code app} module orchestrates both this call and
 * the actual token minting together, the same layering {@code
 * RefreshTokenRotationAuthenticationProvider} already establishes for token issuance that spans
 * more than one module.
 */
public record ImpersonateAccountCommand(AccountId accountId, AuditActor actor) {}
