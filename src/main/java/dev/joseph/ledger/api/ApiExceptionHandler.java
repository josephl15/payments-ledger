package dev.joseph.ledger.api;

import dev.joseph.ledger.service.AccountNotUsableException;
import dev.joseph.ledger.service.DuplicateUsernameException;
import dev.joseph.ledger.service.IdempotencyKeyMismatchException;
import dev.joseph.ledger.service.InsufficientFundsException;
import dev.joseph.ledger.service.InvalidCredentialsException;
import dev.joseph.ledger.service.InvalidRequestException;
import dev.joseph.ledger.service.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into an RFC 7807 {@code application/problem+json} response, in one place.
 *
 * <p>{@code @RestControllerAdvice} makes this class apply to all controllers. It extends Spring's
 * ResponseEntityExceptionHandler, which already maps framework errors (unreadable JSON such as a decimal amount,
 * a missing header, a wrong parameter type) to problem+json with status 400. This class adds the ledger's own
 * exceptions and lists the field errors for Bean Validation failures. It never echoes the request body back.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds", ex.getMessage());
    }

    @ExceptionHandler(AccountNotUsableException.class)
    ProblemDetail accountNotUsable(AccountNotUsableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Account cannot be used", ex.getMessage());
    }

    /** The same Idempotency-Key was sent with a different request: 422, so the client knows it reused a key. */
    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    ProblemDetail idempotencyKeyMismatch(IdempotencyKeyMismatchException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", ex.getMessage());
    }

    /** Registration with a taken username: 409. (The unique constraint on users.username is the real guarantee.) */
    @ExceptionHandler(DuplicateUsernameException.class)
    ProblemDetail duplicateUsername(DuplicateUsernameException ex) {
        return problem(HttpStatus.CONFLICT, "Username taken", ex.getMessage());
    }

    /** Wrong password and unknown username produce this same response, so usernames cannot be enumerated. */
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> invalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, "Invalid credentials", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    /** Anything unexpected: a generic 500 that leaks nothing; the details go to the log, not to the client. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    /** Bean Validation failure on a request body: 400 plus the list of offending fields (names and rules only). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "The request body is not valid");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
