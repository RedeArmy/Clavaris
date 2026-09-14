package com.clavaris.clientregistry.application.usecase.listorganizationclientspaged;

import com.clavaris.clientregistry.domain.model.OrganizationClient;
import com.clavaris.common.domain.model.KeysetPage;

/**
 * TD-PERF-020 (keyset revision, 2026-09-14): the dashboard's own paginated sibling of {@code
 * ListOrganizationClientsUseCase} — that use case stays unbounded and remains the one {@code
 * PlatformOrganizationClientController} uses for its own anti-enumeration ownership check
 * (deactivate/rotate-secret must recognize a clientId regardless of which page the dashboard
 * happens to be showing); this one exists only for the list page's own display.
 */
@FunctionalInterface
public interface ListOrganizationClientsPagedUseCase {

  KeysetPage<OrganizationClient> handle(ListOrganizationClientsPagedQuery query);
}
