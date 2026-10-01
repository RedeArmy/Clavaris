package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * @param credential the raw JSON body of the browser's {@code navigator.credentials.create()}
 *     result.
 * @param nickname optional user-supplied label (e.g. "MacBook Touch ID") — display only.
 */
public record WebAuthnFinishRegistrationRequest(String credential, String nickname) {}
