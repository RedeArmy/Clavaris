package com.clavaris.identity.application.usecase.purgesigningkeyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * TD-SEC-029: reserved for a <b>confirmed</b> compromise
 * (`incident-response-signing-key-compromise.md` §3.6) — targets one specific {@code kid}, not just
 * "whichever key is currently active", since the compromised key may already have been rotated away
 * and only discovered afterward. Never self-service, same platform-tier-only posture as {@code
 * RotateSigningKeyForOrganizationCommand}.
 *
 * @param actor the calling {@code PlatformClient}, resolved by the controller from the request's
 *     own {@code Authentication}.
 */
public record PurgeSigningKeyForOrganizationCommand(
    OrganizationId organizationId, String kid, AuditActor actor) {}
