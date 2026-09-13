package com.clavaris.organization.application.usecase.deleteorganization;

import com.clavaris.common.domain.model.AuditActor;
import java.util.UUID;

/**
 * BR-DATA-02/03's own organization-level equivalent — the single most destructive operation this
 * management API exposes (an entire consuming system's whole account pool, not one identity).
 *
 * <p><b>Corrected, TD-FUT-032 (2026-09-13):</b> this Javadoc previously claimed {@code actor} was
 * "always a {@code AuditActor#platformClient}... never self-service by an Organization's own owning
 * {@code PlatformAccount}." That is no longer accurate as written — same "who may call it changed,
 * not what the invariant requires" correction {@code CreateWorkspaceCommand}'s own Javadoc already
 * documents for an identical situation. {@code actor} is now either {@link
 * AuditActor#platformClient} (the REST admin API, unchanged) or {@link AuditActor#platformAccount}
 * (the dashboard's own Danger Zone, gated behind a real confirmation flow — a fresh, single-use,
 * short-TTL server-side token plus a required exact-name-match input — {@code
 * PlatformDeleteOrganizationController}'s own Javadoc has the full design). The destructive
 * *consequences* this command carries out are completely unchanged by who is allowed to trigger it.
 */
public record DeleteOrganizationCommand(UUID organizationId, AuditActor actor) {}
