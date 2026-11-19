package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * HTTP request body for {@code PUT /api/v1/admin/accounts/{id}/profile} (TD-FUT-041). Every field
 * is nullable, same "omitted means leave unconfigured" convention {@code SetClientBrandingRequest}
 * already establishes — {@code firstName}/{@code lastName} plain-overwrite when present (never
 * cleared to blank by omission), {@code username}/{@code phoneNumber} apply only when the Account
 * doesn't already have one (see {@code UpdateAccountProfileCommand}'s own Javadoc for why — the
 * exact same rule this request's own values feed into, unchanged for this second, Backend-API
 * caller).
 */
public record UpdateAccountProfileRequest(
    @Size(max = MAX_NAME_LENGTH) String firstName,
    @Size(max = MAX_NAME_LENGTH) String lastName,
    @Pattern(regexp = USERNAME_SHAPE) String username,
    @Size(max = MAX_PHONE_LENGTH) @Pattern(regexp = PHONE_SHAPE) String phoneNumber) {

  // TD-ARCH-060 / BR-ADMIN-03: the bounds of the columns they land in (accounts.first_name and
  // last_name are varchar(255), phone_number varchar(32)) and the shape Username enforces, stated
  // at the boundary so a bad value is a 400 instead of a 500. The phone number is a display
  // string ("+502 5555-0100": a country code, a space, a local number), so the shape is digits
  // with an optional leading plus or parenthesis and the usual separators, not a full E.164 parse.
  /* package */ static final int MAX_NAME_LENGTH = 255;
  /* package */ static final int MAX_PHONE_LENGTH = 32;
  /* package */ static final String USERNAME_SHAPE = "^\\s*[A-Za-z0-9_-]{3,32}\\s*$";
  /* package */ static final String PHONE_SHAPE = "^[+(]?\\d[\\d ().\\-/]*$";
}
