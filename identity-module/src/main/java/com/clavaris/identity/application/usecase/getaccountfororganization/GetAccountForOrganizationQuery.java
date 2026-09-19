package com.clavaris.identity.application.usecase.getaccountfororganization;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * SDE-III review, 2026-09-19 — Clerk dashboard "View Profile" parity. Carries both ids so the
 * service can reject an {@code accountId} that resolves to a real Account belonging to a different
 * Organization, same anti-enumeration posture as {@code OrganizationForPlatformAccountResolver}.
 */
public record GetAccountForOrganizationQuery(OrganizationId organizationId, AccountId accountId) {}
