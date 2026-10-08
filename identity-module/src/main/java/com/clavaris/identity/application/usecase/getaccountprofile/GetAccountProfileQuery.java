package com.clavaris.identity.application.usecase.getaccountprofile;

import com.clavaris.identity.domain.model.AccountId;

/**
 * TD-FUT-041: Backend-API-style profile read, by {@code accountId} alone — unlike {@code
 * getaccountfororganization}'s own query, there is no {@code organizationId} to additionally match
 * against here, since the REST route this backs ({@code GET /api/v1/admin/accounts/{id}/profile})
 * never carries one in its path, same shape {@code UpdateAccountMetadataCommand}'s own {@code
 * accountId}-only lookup already establishes for the sibling metadata endpoint (TD-FUT-034).
 */
public record GetAccountProfileQuery(AccountId accountId) {}
