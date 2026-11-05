package com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization;

import java.util.UUID;

public record PreviewAccessRestrictionForOrganizationQuery(UUID organizationId, String email) {}
