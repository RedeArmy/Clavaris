package com.clavaris.identity.application.usecase.recordloginevent;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;

/** Input to {@link RecordLoginEventUseCase} — TD-FUT-034, Clerk activity heatmap parity. */
public record RecordLoginEventCommand(AccountId accountId, OrganizationId organizationId) {}
