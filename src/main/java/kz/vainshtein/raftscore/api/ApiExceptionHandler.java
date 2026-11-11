package kz.vainshtein.raftscore.api;

import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts supported request and concurrency failures to stable API errors. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({org.springframework.transaction.TransactionException.class, org.springframework.dao.DataAccessResourceFailureException.class, org.hibernate.exception.JDBCConnectionException.class, java.sql.SQLException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception exception) throws Exception {
        if (!DatabaseUnavailable.isCausedBy(exception)) {
            throw exception;
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(DatabaseUnavailable.error());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedRequest() {
        return ResponseEntity.badRequest().body(ApiError.of("request.malformed"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), "validation.invalid", Map.of()))
                .toList();
        return ResponseEntity.badRequest().body(new ApiError("request.validation_failed", Map.of(), fields));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException exception) {
        var field = new ApiError.FieldError(exception.getParameterName(), "validation.required", Map.of());
        return ResponseEntity.badRequest()
                .body(new ApiError("request.validation_failed", Map.of(), List.of(field)));
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("conflict.optimistic_lock"));
    }
}
