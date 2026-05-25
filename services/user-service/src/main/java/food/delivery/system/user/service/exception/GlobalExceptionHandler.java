package food.delivery.system.user.service.exception;

import food.delivery.system.common.libs.api.ApiError;
import food.delivery.system.common.libs.api.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
        log.warn("Authentication failed for request {}: {}", req.getRequestURI(), ex.getMessage());
        return new ApiError(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                "AUTHENTICATION_FAILED",
                "Invalid credentials",
                Instant.now(),
                req.getRequestURI(),
                null,
                null
        );
    }

    @ExceptionHandler(DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleDuplicateEmail(DuplicateEmailException ex, HttpServletRequest req) {
        log.warn("Duplicate email registration attempt: {}", ex.getMessage());
        return new ApiError(
                HttpStatus.CONFLICT.value(),
                "Conflict",
                "EMAIL_ALREADY_REGISTERED",
                ex.getMessage(),
                Instant.now(),
                req.getRequestURI(),
                null,
                null
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
                .toList();

        return new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "VALIDATION_FAILED",
                "Request validation failed",
                Instant.now(),
                req.getRequestURI(),
                null,
                fieldErrors
        );
    }
}
