package com.github.lisama569.webhookingestiongatewayjava.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 通用异常处理：非法请求体 / 缺失字段返回 400，其余返回 500。
 */
@RestControllerAdvice
public class WebhookExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
                "disposition", "BAD_REQUEST",
                "reason", e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception e) {
        log.error("处理回调时发生未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "disposition", "ERROR",
                "reason", e.getMessage() == null ? e.toString() : e.getMessage()));
    }
}
