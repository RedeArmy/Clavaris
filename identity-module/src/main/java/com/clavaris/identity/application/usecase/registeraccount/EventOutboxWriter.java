package com.clavaris.identity.application.usecase.registeraccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Outbound port — ADR-0007 §1: the row this writes must land in the SAME database transaction as
 * the domain state change it records, which is why {@link RegisterAccountService} calls this (not a
 * direct event bus publish) from inside its own {@code @Transactional} method. Write-only in this
 * slice: the dispatcher that drains {@code event_outbox} and actually delivers webhooks belongs to
 * {@code webhook-module} (ADR-0007, 🟡 proposed), not yet built — a row written here simply waits
 * until that module exists to drain it, same as any other outbox consumer coming online later
 * would.
 */
@FunctionalInterface
public interface EventOutboxWriter {

  void write(String eventType, AccountId aggregateId, Object payload);
}
