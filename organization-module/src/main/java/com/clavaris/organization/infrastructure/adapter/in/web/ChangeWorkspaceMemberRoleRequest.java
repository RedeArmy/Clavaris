package com.clavaris.organization.infrastructure.adapter.in.web;

import java.util.UUID;

/**
 * {@code roleId} (ADR-0027 — replaces the old fixed-enum {@code role}) is nullable by design: a
 * {@code null} value unassigns the membership's role entirely (ADR-0027 §5), an explicitly allowed
 * state, not an invalid request.
 */
public record ChangeWorkspaceMemberRoleRequest(UUID roleId) {}
