package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.SessionPolicy;
import java.time.Instant;
import java.util.UUID;

@SuppressWarnings("PMD.LongVariable")
public record SetSessionPolicyResponse(
    UUID organizationId,
    int maximumLifetimeMinutes,
    int inactivityTimeoutMinutes,
    int reverificationWindowMinutes,
    boolean multiSessionHandlingEnabled,
    Instant updatedAt) {

  public static SetSessionPolicyResponse from(final SessionPolicy policy) {
    return new SetSessionPolicyResponse(
        policy.organizationId(),
        policy.maximumLifetimeMinutes(),
        policy.inactivityTimeoutMinutes(),
        policy.reverificationWindowMinutes(),
        policy.multiSessionHandlingEnabled(),
        policy.updatedAt());
  }
}
