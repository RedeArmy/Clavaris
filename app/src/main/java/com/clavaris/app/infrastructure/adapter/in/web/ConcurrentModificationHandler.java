package com.clavaris.app.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.ConcurrentAccountModificationException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * TD-ARCH-037: turns "another writer changed this account first" into a 409 on every surface that
 * can write an account, REST admin API and operator dashboard alike. The dashboard's HTMX layer
 * already maps a 409 to its "conflicts with a recent change, reload and try again" toast, so no
 * page needs its own handling; a plain form post sees the JSON body, which is rare and still says
 * what happened.
 *
 * <p>Highest precedence on purpose: {@link GlobalExceptionHandler}'s catch-all would otherwise
 * claim the exception for REST controllers first and answer 500.
 */
// PMD.AtLeastOneConstructor: no state, only the handler — same precedent as GlobalExceptionHandler.
@SuppressWarnings("PMD.AtLeastOneConstructor")
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ConcurrentModificationHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ConcurrentModificationHandler.class);

  @ExceptionHandler(ConcurrentAccountModificationException.class)
  /* package */ ResponseEntity<ConflictResponse> handleConcurrentAccountModification(
      final ConcurrentAccountModificationException exception) {
    final String correlationId = UUID.randomUUID().toString();
    // The message names an account id, which is an identifier, not PII, but there is no reason for
    // the response to carry it: the client already knows which account it was editing.
    LOG.info("event=concurrent_account_modification correlationId={}", correlationId);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ConflictResponse(
                "concurrent_modification",
                "This account was changed by someone else. Reload and try again.",
                correlationId,
                Instant.now()));
  }

  /** Four fixed fields, none of them derived from the exception or the request. */
  /* package */ record ConflictResponse(
      String error, String message, String correlationId, Instant timestamp) {}
}
