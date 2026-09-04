package com.clavaris.identity.application.usecase.impersonateaccount;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * {@code organizationId} is the one fact the {@code app}-module orchestrator can't derive from a
 * bare {@link AccountId} on its own — needed to resolve the target {@code OAuthClient} (must belong
 * to the same Organization, BR-ORG-02/ADR-0010) and the signing key to mint under.
 */
public record ImpersonateAccountResult(AccountId accountId, OrganizationId organizationId) {}
