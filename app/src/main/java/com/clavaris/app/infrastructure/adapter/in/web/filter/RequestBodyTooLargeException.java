package com.clavaris.app.infrastructure.adapter.in.web.filter;

import java.io.IOException;

/**
 * Raised by {@link CachedBodyHttpServletRequest} when a request body exceeds the cap the filter
 * that buffers it is willing to hold in memory. An {@link IOException} so the buffering
 * constructor's existing signature does not change; {@link IdempotencyKeyFilter} catches it
 * specifically and answers 413.
 */
final class RequestBodyTooLargeException extends IOException {

  private static final long serialVersionUID = 1L;

  /* default */ RequestBodyTooLargeException(final long maxBodyBytes) {
    super("Request body exceeds the " + maxBodyBytes + "-byte limit");
  }
}
