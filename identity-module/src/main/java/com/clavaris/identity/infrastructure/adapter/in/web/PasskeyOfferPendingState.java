package com.clavaris.identity.infrastructure.adapter.in.web;

/**
 * ADR-0039: the one {@code HttpSession} attribute the passkey offer is carried on, the URL the
 * person was going to when the offer interrupted them. It is stored server-side, never taken from
 * the request, so the offer cannot be turned into an open redirect. A plain String, for the same
 * Redis session serializer reason {@link DeviceTrustPendingState} documents.
 */
/* package */ final class PasskeyOfferPendingState {

  /* package */ static final String NEXT_ATTRIBUTE = "clavaris.passkeyOffer.next";

  private PasskeyOfferPendingState() {
    // Constants only.
  }
}
