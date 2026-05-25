package food.delivery.system.user.service.exception;

import food.delivery.system.common.libs.api.ApiError;
import food.delivery.system.common.libs.api.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError handleAuthFailure(RuntimeException ex, HttpServletRequest req) {
        log.warn("Authentication failed [{}]: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid credentials", req, null);
    }

    @ExceptionHandler(EmailAlreadyTakenException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleEmailTaken(EmailAlreadyTakenException ex, HttpServletRequest req) {
        log.warn("Duplicate email [{}]: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "AUTH_EMAIL_TAKEN", ex.getMessage(), req, null);
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleUserNotFound(UserNotFoundException ex, HttpServletRequest req) {
        log.warn("User not found [{}]: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage(), req, null);
    }

    @ExceptionHandler(InvalidTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError handleInvalidToken(InvalidTokenException ex, HttpServletRequest req) {
        log.warn("Token rejected [{}]: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", ex.getMessage(), req, null);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiError> handleAccountLocked(AccountLockedException ex, HttpServletRequest req) {
        log.warn("Account locked [{}]: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(error(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED", ex.getMessage(), req, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", req, fieldErrors);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error [{}]", req.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred", req, null);
    }

    private ApiError error(HttpStatus status, String code, String message, HttpServletRequest req,
                           List<FieldError> fieldErrors) {
        return new ApiError(status.value(), status.getReasonPhrase(), code, message,
                Instant.now(), req.getRequestURI(), null, fieldErrors);
    }
}
