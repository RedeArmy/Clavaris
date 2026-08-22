package com.clavaris.identity.application.usecase.confirmemailverification;

/**
 * @param presentedRawToken the value from the emailed link's query parameter — never the hash,
 *     never persisted as-is, same convention as {@code
 *     rotaterefreshtoken.RotateRefreshTokenCommand}
 */
public record ConfirmEmailVerificationCommand(String presentedRawToken) {}
