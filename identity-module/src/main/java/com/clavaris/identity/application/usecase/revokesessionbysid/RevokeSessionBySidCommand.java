package com.clavaris.identity.application.usecase.revokesessionbysid;

import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Objects;

/**
 * @param sid the {@code sid} claim of the ID token an RP-Initiated Logout named
 * @param organizationId the Organization whose logout endpoint received the request
 */
public record RevokeSessionBySidCommand(String sid, OrganizationId organizationId) {

  public RevokeSessionBySidCommand {
    Objects.requireNonNull(sid, "sid must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
  }
}
