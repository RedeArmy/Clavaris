package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;

@FunctionalInterface
public interface AddAccessRestrictionEntryUseCase {

  AccessRestrictionEntry handle(AddAccessRestrictionEntryCommand command);
}
