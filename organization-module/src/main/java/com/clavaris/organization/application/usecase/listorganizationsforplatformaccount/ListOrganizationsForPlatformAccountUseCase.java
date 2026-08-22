package com.clavaris.organization.application.usecase.listorganizationsforplatformaccount;

import com.clavaris.organization.domain.model.Organization;
import java.util.List;

/** ADR-0012: the dashboard's own "your organizations" query. */
@FunctionalInterface
public interface ListOrganizationsForPlatformAccountUseCase {

  List<Organization> handle(ListOrganizationsForPlatformAccountQuery query);
}
