package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

import java.util.UUID;

/**
 * A minimal, display-only projection of an identity-module {@code Account} — {@code accountId} plus
 * a human-readable {@code label} (its email), never the real {@code Account} type itself. Same
 * "deliberately does NOT reference any identity-module type directly" posture {@link
 * OrganizationAccountDirectory}'s own Javadoc documents for the port this backs.
 */
public record OrganizationAccountSummary(UUID accountId, String label) {}
