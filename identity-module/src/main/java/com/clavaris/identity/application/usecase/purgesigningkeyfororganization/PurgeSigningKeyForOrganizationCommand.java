package com.clavaris.identity.application.usecase.purgesigningkeyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.OrganizationId;

/**
 * TD-SEC-029: reserved for a <b>confirmed</b> compromise
 * (`incident-response-signing-key-compromise.md` §3.6) — targets one specific {@code kid}, not just
 * "whichever key is currently active", since the compromised key may already have been rotated away
 * and only discovered afterward. Never self-service.
 *
 * <p>SDE-III review, 2026-09-11: reviewed alongside the identical widening applied to {@code
 * RotateSigningKeyForOrganizationCommand}'s own actor, and deliberately left unchanged — unlike
 * rotation, this operation has zero overlap by design (see {@code SigningKey#purgeImmediately()}'s
 * own Javadoc) and breaks every currently-valid token signed under the purged key immediately. A
 * self-service purge button an Organization owner could trigger by mistake carries real,
 * un-undoable blast radius for their own users; the dashboard shows this operation's existence but
 * never a way to invoke it — see {@code PlatformSigningKeyController}'s own Javadoc.
 *
 * @param actor the calling {@code PlatformClient}, resolved by the controller from the request's
 *     own {@code Authentication}.
 */
public record PurgeSigningKeyForOrganizationCommand(
    OrganizationId organizationId, String kid, AuditActor actor) {}
