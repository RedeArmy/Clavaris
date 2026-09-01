package com.clavaris.identity.domain.event;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.KnownDevice;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.UUID;

/**
 * TD-SEC-034 follow-up (SDE-III review, 2026-08-31): {@code account.new_device_detected} — written
 * to the transactional outbox (ADR-0007 §1) alongside the audit entry {@code
 * RecordAccountLoginDeviceService} already writes for this same event; same "every real
 * security-relevant account mutation gets one" precedent {@link AccountSuspendedEvent}/{@link
 * AccountReactivatedEvent} already established — this class previously audited a new-device login
 * but never raised an event a future webhook/alerting consumer could react to. {@code deviceId} is
 * {@link KnownDevice}'s own id, never the {@code User-Agent} string or the device token itself
 * (BR-DATA-01) — a consumer that needs those can already resolve them, scoped to this one Account,
 * via the sessions/devices page; this event only needs to say a new device was seen and which row
 * records it.
 */
public record AccountNewDeviceDetectedEvent(
    AccountId accountId, OrganizationId organizationId, UUID deviceId, Instant occurredAt) {

  public static AccountNewDeviceDetectedEvent from(
      final KnownDevice device, final OrganizationId organizationId) {
    return new AccountNewDeviceDetectedEvent(
        device.accountId(), organizationId, device.id(), Instant.now());
  }
}
