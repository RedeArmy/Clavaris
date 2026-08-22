package com.clavaris.organization.application.usecase.listorganizationsforplatformaccount;

import java.util.UUID;

@SuppressWarnings("PMD.LongVariable")
public record ListOrganizationsForPlatformAccountQuery(UUID ownerPlatformAccountId) {}
