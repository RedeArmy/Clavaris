package com.clavaris.identity.application.usecase.confirmnewdeviceloginalert;

/**
 * @param presentedRawToken the value from the emailed "this wasn't me" link's query parameter —
 *     never the hash, never persisted as-is, same convention as {@code
 *     confirmpendingsociallink.ConfirmPendingSocialLinkCommand}
 */
public record ConfirmNewDeviceLoginAlertCommand(String presentedRawToken) {}
