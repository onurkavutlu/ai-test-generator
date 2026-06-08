package com.testgen.config;

import com.testgen.llm.LlmException;
import com.testgen.service.TestGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(IllegalArgumentException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TestGenerationException.class)
    public ResponseEntity<Map<String, Object>> handleGeneration(TestGenerationException e) {
        log.error("Test üretim hatası", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, Object>> handleLlm(LlmException e) {
        log.error("LLM hatası", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "LLM servisi yanıt vermedi: " + e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, errors);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "Kaynak bulunamadi: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Beklenmeyen hata", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Beklenmeyen hata: " + e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", message,
                "status", status.value(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
