package com.clavaris.identity.application.usecase.impersonateaccount;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Impersonate user" menu item, dashboard-modal half.
 * Outbound port to the actual JWT-minting machinery, which lives in {@code app} (Spring
 * Authorization Server internals — {@code JwtGenerator}, per-Organization {@code JWKSource}, the
 * synthetic {@code AuthorizationServerContext}) and cannot be depended on directly from
 * identity-module (CLAUDE.md §7.2: {@code app} depends on every module, never the reverse).
 * Implemented by a bridge in {@code app} delegating to the existing {@code
 * ImpersonationTokenIssuer} — the same one the REST {@code POST
 * /api/v1/admin/accounts/{id}:impersonate} endpoint already uses, so the dashboard modal mints
 * byte-for-byte the same kind of token, not a parallel implementation.
 *
 * <p>{@link ImpersonateAccountUseCase} (identity-module's own half) validates the Account itself
 * (exists, {@code ACTIVE}) and writes the audit event; this port validates/resolves {@code
 * clientId}/scopes and performs the actual signing — same split the REST controller's own two
 * collaborators already establish.
 *
 * @throws ImpersonationClientNotFoundException if {@code clientId} doesn't resolve to an active
 *     {@code OAuthClient} belonging to {@code organizationId}
 * @throws ImpersonationScopeNotAllowedException if {@code scopes} contains anything outside the
 *     resolved client's own {@code allowedScopes}
 */
@FunctionalInterface
public interface ImpersonationTokenMinter {

  MintedImpersonationToken mint(
      AccountId accountId,
      OrganizationId organizationId,
      String clientId,
      List<String> scopes,
      AuditActor actor,
      String baseUrl);
}
