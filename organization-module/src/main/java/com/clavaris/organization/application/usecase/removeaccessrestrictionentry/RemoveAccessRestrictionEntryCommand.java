package com.clavaris.organization.application.usecase.removeaccessrestrictionentry;

import java.util.UUID;

public record RemoveAccessRestrictionEntryCommand(UUID organizationId, UUID entryId) {}
