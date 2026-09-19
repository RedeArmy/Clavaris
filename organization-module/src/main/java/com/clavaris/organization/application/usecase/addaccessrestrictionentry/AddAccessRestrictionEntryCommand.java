package com.clavaris.organization.application.usecase.addaccessrestrictionentry;

import com.clavaris.organization.domain.model.RestrictionType;
import java.util.UUID;

public record AddAccessRestrictionEntryCommand(
    UUID organizationId, RestrictionType type, String identifier) {}
