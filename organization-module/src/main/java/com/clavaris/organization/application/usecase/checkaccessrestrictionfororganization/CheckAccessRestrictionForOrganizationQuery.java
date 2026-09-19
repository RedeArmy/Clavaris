package com.clavaris.organization.application.usecase.checkaccessrestrictionfororganization;

import java.util.UUID;

public record CheckAccessRestrictionForOrganizationQuery(UUID organizationId, String email) {}
