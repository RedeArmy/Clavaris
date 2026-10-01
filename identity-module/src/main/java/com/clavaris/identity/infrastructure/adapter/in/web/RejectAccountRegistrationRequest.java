package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * TD-FUT-019: optional body for {@code RejectAccountRegistrationController}. {@code reason} is
 * {@code null}/blank legal — see {@code RejectAccountRegistrationCommand}'s own Javadoc.
 */
public record RejectAccountRegistrationRequest(String reason) {}
