package com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged;

import com.clavaris.common.domain.model.Page;
import com.clavaris.organization.domain.model.Organization;

/**
 * TD-PERF-020: the dashboard's own paginated sibling of {@code
 * ListOrganizationsForPlatformAccountUseCase} — that use case stays exactly as it was (still
 * returns the full, unbounded list) since nothing else in this codebase needs it paginated; this
 * one exists only for {@code PlatformOrganizationDashboardController}'s own list page.
 */
@FunctionalInterface
public interface ListOrganizationsForPlatformAccountPagedUseCase {

  Page<Organization> handle(ListOrganizationsForPlatformAccountPagedQuery query);
}
