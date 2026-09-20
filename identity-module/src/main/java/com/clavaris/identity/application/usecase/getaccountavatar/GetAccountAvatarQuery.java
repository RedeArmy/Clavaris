package com.clavaris.identity.application.usecase.getaccountavatar;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * Input to {@link GetAccountAvatarUseCase}. {@code organizationId} is required even though {@code
 * accountId} alone already uniquely identifies a row — ADR-0010's tenant isolation boundary applies
 * to every read this codebase exposes, including this one, which (unlike almost everything else in
 * {@code identity-module}) is served with no authentication at all (a browser's own {@code <img
 * src>} can never carry a bearer token) — see {@code GetAccountAvatarService}'s own Javadoc for why
 * a mismatched Organization is treated identically to "unknown Account", never a distinguishable
 * error.
 */
public record GetAccountAvatarQuery(OrganizationId organizationId, AccountId accountId) {}
