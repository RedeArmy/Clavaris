package com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import java.util.List;

@FunctionalInterface
public interface ListAccessRestrictionEntriesForOrganizationUseCase {

  List<AccessRestrictionEntry> handle(ListAccessRestrictionEntriesForOrganizationQuery query);
}
