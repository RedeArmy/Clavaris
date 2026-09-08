package com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert;

/**
 * @param presentedRawToken the value from the emailed "this wasn't me" link's query parameter —
 *     never the hash, never persisted as-is, same convention as {@code
 *     confirmnewdeviceloginalert.ConfirmNewDeviceLoginAlertCommand}
 */
public record ConfirmNewPlatformDeviceLoginAlertCommand(String presentedRawToken) {}
