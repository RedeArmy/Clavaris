package com.clavaris.identity.application.usecase.impersonateaccount;

import com.clavaris.identity.domain.model.OrganizationId;
import java.util.List;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "Impersonate user" menu item: populates the modal's
 * client picker. Outbound port to client-registry-module, which identity-module cannot depend on
 * directly (CLAUDE.md §7.2) — same cross-module-port-plus-bridge pattern as {@code
 * OrganizationForClientResolver}, implemented by a bridge in {@code app} delegating to
 * client-registry-module's own {@code ListOAuthClientsUseCase}.
 */
@FunctionalInterface
public interface OAuthClientsForOrganizationProvider {

  /** Active OAuth Clients only — an inactive one can never be impersonated-as (ADR-0023). */
  List<OAuthClientSummary> forOrganization(OrganizationId organizationId);
}
