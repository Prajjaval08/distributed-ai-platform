package com.distributedai.api.exception;

import com.distributedai.api.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailExists(
            EmailAlreadyExistsException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withLoggedCode(
                        "EMAIL_ALREADY_EXISTS",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler({
            InvalidCredentialsException.class,
            BadCredentialsException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            RuntimeException ex) {

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(withLoggedCode(
                        "UNAUTHORIZED",
                        "Invalid email or password"
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError ->
                        fieldError.getField()
                                + ": "
                                + fieldError.getDefaultMessage()
                )
                .collect(Collectors.joining("; "));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(withLoggedCode(
                        "INVALID_REQUEST",
                        message
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex) {

        // Log the complete exception and stack trace
        log.error(
                "Unexpected exception while processing request",
                ex
        );

        // Do not expose internal exception details to the client
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withLoggedCode(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred"
                ));
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleJobNotFound(
            JobNotFoundException ex) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(withLoggedCode(
                        "JOB_NOT_FOUND",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(JobNotCancellableException.class)
    public ResponseEntity<ErrorResponse> handleJobNotCancellable(
            JobNotCancellableException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withLoggedCode(
                        "JOB_NOT_CANCELLABLE",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(JobNotProcessableException.class)
    public ResponseEntity<ErrorResponse> handleJobNotProcessable(
            JobNotProcessableException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withLoggedCode(
                        "JOB_NOT_PROCESSABLE",
                        ex.getMessage()
                ));
    }

    @ExceptionHandler(JobResultNotReadyException.class)
    public ResponseEntity<ErrorResponse> handleResultNotReady(
            JobResultNotReadyException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(withLoggedCode(
                        "RESULT_NOT_READY",
                        ex.getMessage()
                ));
    }

    private ErrorResponse withLoggedCode(
            String errorCode,
            String message) {

        MDC.put("errorCode", errorCode);

        try {
            log.warn(
                    "Request failed: {} - {}",
                    errorCode,
                    message
            );

            return ErrorResponse.of(
                    errorCode,
                    message
            );

        } finally {
            MDC.clear();
        }
    }
}