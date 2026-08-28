package com.clavaris.app.infrastructure.config;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * TD-SEC-015: the owned safety net for an exception nobody anticipated, on the REST/JSON side of
 * this codebase (every {@code @RestController} — the admin API, Workspace, account-lifecycle
 * endpoints). Before this class, that gap was covered only by Spring Boot's own default
 * stack-trace/message suppression in {@code BasicErrorController} — currently secure, but not
 * something this project explicitly configured or tested, so a future framework default change or a
 * stray {@code server.error.*} override would go unnoticed (see {@code application.yml}'s own
 * {@code server.error} block, added alongside this class for the same reason, backing the
 * Thymeleaf-rendered hosted-UI controllers this class deliberately does not cover — see below).
 *
 * <p><b>Deliberately does not replace each controller's own specific {@code catch}</b> — {@code
 * RegisterAccountController}/{@code LoginController}/every other controller in this codebase that
 * explicitly catches and translates an anticipated exception into a meaningful status code (404,
 * 409, ...) keeps doing exactly that; this class only ever sees an exception that escaped every one
 * of those, i.e. the one nobody anticipated. Defense in depth, not a replacement for the specific
 * handling already in place.
 *
 * <p><b>Scoped to {@code @RestController}s only, not the Thymeleaf-rendered hosted-UI
 * controllers</b> ({@code LoginController}, {@code RegisterAccountController}'s own view-returning
 * methods, ...) — {@code @RestControllerAdvice} applies globally across both by default, but every
 * method here returns a JSON body via {@code ResponseEntity}, which would be actively wrong for a
 * controller that's supposed to render an HTML error page instead. Those stay covered by {@code
 * BasicErrorController} (now with an explicitly-configured, not merely default, safe posture — see
 * {@code application.yml}). Revisit this split the day a dedicated branded error page (ADR-0009)
 * makes an HTML-rendering equivalent worth building.
 *
 * <p>Never the exception's own message or stack trace in the response body — same BR-DATA-01
 * discipline as every other user-facing surface in this codebase. The real exception is logged
 * server-side, at ERROR, with a random correlation id also returned to the caller so an operator
 * can find the matching log line without the response itself carrying anything sensitive.
 *
 * <p><b>Extends {@link ResponseEntityExceptionHandler}, not a bare class</b> — real regression
 * caught by the existing suite before this shipped: a first version with no supertype registered
 * {@code handleUnanticipated} as the *only* {@code @ExceptionHandler} match for standard Spring MVC
 * exceptions too (e.g. {@code MethodArgumentNotValidException} from {@code @Valid}), since nothing
 * else in the app registered a more specific handler for them — silently turning {@code
 * CreateOrganizationIntegrationTest}'s own already-passing 400-on-blank-name assertions into 500s.
 * Extending this class inherits Spring's own specific, correct handling for the whole family of
 * standard MVC exceptions (validation errors, malformed JSON, unsupported media type, ...); its
 * handler methods are strictly more specific than this class's own {@code Exception.class}
 * catch-all below, so Spring's exception-handler resolution (by exception-type specificity, not
 * declaration order) always prefers them, leaving the catch-all to see only what neither Spring nor
 * any controller's own {@code catch} already anticipated.
 */
// PMD.AtLeastOneConstructor: this class holds no state of its own beyond the static LOG field —
// same "intentionally empty" precedent WorkspaceAwareOidcUserInfoMapper's own identical
// suppression already establishes.
@SuppressWarnings("PMD.AtLeastOneConstructor")
@RestControllerAdvice(annotations = org.springframework.web.bind.annotation.RestController.class)
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // PMD.GuardLogStatement false positive — same rationale as every other logging call site in
  // this codebase (e.g. DeleteAccountService's own identical suppression): every argument below is
  // a cheap accessor (a freshly-generated UUID string, a class name), not an expensive computation
  // a guard would meaningfully skip.
  @SuppressWarnings("PMD.GuardLogStatement")
  @ExceptionHandler(Exception.class)
  /* package */ ResponseEntity<ErrorResponse> handleUnanticipated(final Exception exception) {
    final String correlationId = UUID.randomUUID().toString();
    // BR-DATA-01: the exception's own message/stack trace never crosses into a log field a client
    // could ever see — this is a server-side-only log line, same "no PII in logs" bar as
    // everything else, but the exception itself is exactly what an operator needs to diagnose an
    // unanticipated failure, so it's logged here (not suppressed) rather than only correlated.
    LOG.error(
        "event=unhandled_exception correlationId={} exceptionType={}",
        correlationId,
        exception.getClass().getName(),
        exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse("internal_error", correlationId, Instant.now()));
  }

  /** Deliberately minimal — no field here is ever anything other than these three static shapes. */
  private record ErrorResponse(String error, String correlationId, Instant timestamp) {}
}
