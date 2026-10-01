package com.clavaris.identity.application.usecase.registeraccount;

/**
 * Outbound port — implemented in {@code
 * infrastructure/adapter/out/breachcheck/PwnedPasswordsBreachedPasswordChecker} (BR-ID-07, a
 * k-anonymity range query against HIBP's own Pwned Passwords API). Called immediately alongside
 * {@code PasswordPolicy.isSatisfiedBy(...)} at every real password-set/password-change use case
 * (registration, self-service reset, forced reset, admin-created accounts, both tiers) — see each
 * call site's own comment for why this runs before the password is ever hashed.
 *
 * <p><b>Fail-open contract:</b> implementations must never throw. Any failure reaching this method
 * (network, circuit breaker open, malformed response) is the implementation's own job to catch and
 * treat as "not breached" — same "a side-channel check must never break a core flow" posture {@code
 * RecordLoginEventService}/{@code RecordAccountLoginDeviceService} already establish for their own
 * non-blocking writes, chosen here (confirmed with the user) because Clavaris's own availability
 * for every registration/password-change must never depend on a third-party API's uptime. Callers
 * of this method never need a try/catch of their own.
 */
@FunctionalInterface
public interface BreachedPasswordChecker {

  boolean isBreached(String rawPassword);
}
