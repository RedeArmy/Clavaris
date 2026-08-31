package com.clavaris.identity.application.usecase.recordaccountlogindevice;

import com.clavaris.identity.domain.model.AccountId;

/**
 * @param accountId the just-authenticated Account — always the use case's own result, never client
 *     input.
 * @param userAgent the raw {@code User-Agent} request header at login time; may be {@code null} or
 *     blank for a non-browser client that sent none — {@link RecordAccountLoginDeviceService}
 *     treats that as a no-op (nothing to fingerprint), not an error.
 * @param sourceIp the raw {@code request.getRemoteAddr()} at login time — same plain-extraction
 *     caveat {@code RateLimitIdentifiers.sourceIp} already documents (no reverse proxy in front of
 *     this deployment yet); informational only, included in the notification email, never used as
 *     part of the device fingerprint itself (an IP changes far too often on the same device).
 */
public record RecordAccountLoginDeviceCommand(
    AccountId accountId, String userAgent, String sourceIp) {}
