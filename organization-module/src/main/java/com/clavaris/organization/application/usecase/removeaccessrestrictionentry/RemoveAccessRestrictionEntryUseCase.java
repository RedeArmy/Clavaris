package com.clavaris.organization.application.usecase.removeaccessrestrictionentry;

@FunctionalInterface
public interface RemoveAccessRestrictionEntryUseCase {

  void handle(RemoveAccessRestrictionEntryCommand command);
}
