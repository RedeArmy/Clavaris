package com.clavaris.identity.application.usecase.revokerefreshtoken;

import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Objects;

/**
 * @param rawToken the refresh token exactly as the client presented it; never logged
 * @param organizationId the Organization whose token endpoint received the request: a token that
 *     belongs to an Account of another Organization is treated as unknown (ADR-0010)
 */
public record RevokeRefreshTokenCommand(String rawToken, OrganizationId organizationId) {

  public RevokeRefreshTokenCommand {
    Objects.requireNonNull(rawToken, "rawToken must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
  }
}
