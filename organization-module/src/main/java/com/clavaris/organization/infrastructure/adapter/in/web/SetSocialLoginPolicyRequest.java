package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/social-login-policy}. {@code providers} is a plain
 * list of provider-name strings (e.g. {@code "GOOGLE"}) validated by the use case's own
 * known-provider allowlist (ADR-0020 Decision 5) — not a typed enum here, same module-independence
 * rule {@code Organization.allowedSocialProviders} documents for itself.
 */
public record SetSocialLoginPolicyRequest(boolean enabled, @NotNull List<String> providers) {}
