package ru.sddp.platform.web;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.sddp.platform.service.ResourceNotFoundException;
import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Problem> notFound(ResourceNotFoundException ex) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Problem(404, ex.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MethodArgumentNotValidException.class})
    ResponseEntity<Problem> badRequest(Exception ex) { return ResponseEntity.badRequest().body(new Problem(400, ex.getMessage())); }
    @ExceptionHandler(ru.sddp.platform.service.CompatibilityViolationException.class)
    ResponseEntity<Problem> incompatible(ru.sddp.platform.service.CompatibilityViolationException ex) { return ResponseEntity.badRequest().body(new Problem(400, ex.getMessage())); }
    @ExceptionHandler(ru.sddp.platform.service.PluginGenerationException.class)
    ResponseEntity<Problem> pluginFailure(ru.sddp.platform.service.PluginGenerationException ex) { return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Problem(502, ex.getMessage())); }
    record Problem(int status, String detail, Instant timestamp) { Problem(int status, String detail) { this(status, detail, Instant.now()); } }
}
