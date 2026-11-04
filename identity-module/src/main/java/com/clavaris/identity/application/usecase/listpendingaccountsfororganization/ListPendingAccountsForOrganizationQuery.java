package com.clavaris.identity.application.usecase.listpendingaccountsfororganization;

import com.clavaris.identity.domain.model.OrganizationId;

/**
 * {@code limit} bounds how many Accounts come back; {@code 0} asks only for the count, which is all
 * the Users page needs to show how many are waiting.
 */
public record ListPendingAccountsForOrganizationQuery(OrganizationId organizationId, int limit) {

  public ListPendingAccountsForOrganizationQuery {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
  }
}
