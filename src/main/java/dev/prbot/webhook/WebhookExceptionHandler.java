package dev.prbot.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns webhook failures into clean responses. Standard MVC errors (405, 415, unreadable body) are
 * left to the parent class; messages here never echo the payload, headers or exception details.
 */
@RestControllerAdvice(assignableTypes = WebhookController.class)
public class WebhookExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);

    @ExceptionHandler(JsonProcessingException.class)
    ResponseEntity<Object> invalidPayload(JsonProcessingException e) {
        log.warn("Rejected webhook with an unparseable JSON body");
        return problem(HttpStatus.BAD_REQUEST, "invalid payload");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        log.error("Unexpected error while handling webhook", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal error");
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
