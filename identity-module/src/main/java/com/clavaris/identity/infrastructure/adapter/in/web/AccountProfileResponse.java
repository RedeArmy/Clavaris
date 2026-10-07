package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Username;
import java.util.UUID;

/**
 * TD-FUT-041: shared response shape for {@code GET}/{@code PUT /api/v1/admin/accounts/{id}/profile}
 * — a consuming application's own backend reading back the same fields it can write, same "one
 * response shape either way" convention {@code SetClientBrandingResponse} already establishes for
 * its own sibling read/write pair.
 */
// PMD.ShortVariable: id matches the wire field name (and every sibling response record's own
// identically-named field) — not an organically short name that should grow.
@SuppressWarnings("PMD.ShortVariable")
public record AccountProfileResponse(
    UUID id, String email, String firstName, String lastName, String username, String phoneNumber) {

  public static AccountProfileResponse from(final Account account) {
    return new AccountProfileResponse(
        account.id().value(),
        account.email().value(),
        account.firstName().orElse(null),
        account.lastName().orElse(null),
        account.username().map(Username::value).orElse(null),
        account.phoneNumber().orElse(null));
  }
}
