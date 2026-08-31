package com.clavaris.identity.application.usecase.confirmpendingsociallink;

/**
 * @param presentedRawToken the value from the emailed confirmation link's query parameter — never
 *     the hash, never persisted as-is, same convention as {@code
 *     confirmemailverification.ConfirmEmailVerificationCommand}
 */
public record ConfirmPendingSocialLinkCommand(String presentedRawToken) {}
