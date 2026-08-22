package com.clavaris.identity.application.usecase.requestpasswordreset;

/**
 * Inbound port — the web adapter depends on this interface, never on {@link
 * RequestPasswordResetService} directly.
 *
 * <p>Deliberately never throws for "no such account": this use case's own contract is to behave
 * identically whether or not {@code command.email()} resolves to a real account in {@code
 * command.organizationId()} — a caller-observable difference here is a user-enumeration oracle (an
 * attacker could learn which emails are registered by watching for a different response), so the
 * "account not found" path and the "email sent" path are indistinguishable from the outside on
 * purpose.
 */
@FunctionalInterface
public interface RequestPasswordResetUseCase {

  void handle(RequestPasswordResetCommand command);
}
