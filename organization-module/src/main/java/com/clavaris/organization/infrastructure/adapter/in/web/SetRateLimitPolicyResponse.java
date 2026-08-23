package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.time.Instant;
import java.util.UUID;

public record SetRateLimitPolicyResponse(
    UUID organizationId, int requestsPerMinute, Instant updatedAt) {

  public static SetRateLimitPolicyResponse from(final RateLimitPolicy policy) {
    return new SetRateLimitPolicyResponse(
        policy.organizationId(), policy.requestsPerMinute(), policy.updatedAt());
  }
}
