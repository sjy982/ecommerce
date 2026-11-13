package com.ecommerce.common.handler;

import com.ecommerce.stock.exception.NotOnSaleException;
import com.ecommerce.stock.exception.SoldOutException;
import com.ecommerce.stock.exception.StockUnavailableException;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import com.ecommerce.common.response.ApiResponse;
import com.ecommerce.common.response.ApiResponseUtil;
import com.ecommerce.payment.exception.PaymentFailedException;
import com.ecommerce.store.Exception.InvalidPasswordException;
import com.ecommerce.user.Exception.RefreshTokenException;
import com.ecommerce.user.Exception.SessionExpiredException;

import lombok.extern.slf4j.Slf4j;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    //DTO 유효성 검사 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(MethodArgumentNotValidException ex) {

        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));

        log.error("Validation failed for fields: {} | Details: {}", errors.keySet(), ex.getMessage());
        return ResponseEntity.status(httpStatus)
                             .body(ApiResponseUtil.createResponse(httpStatus.value(), errors,"Validation failed. Please correct the highlighted fields."));
    }

    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleSessionExpiredException(SessionExpiredException ex) {
        log.warn("Session expired for user. Reason: {}", ex.getMessage());
        HttpStatus httpStatus = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(httpStatus)
                .body(ApiResponseUtil.createResponse(httpStatus.value(), "session has expired."));
    }

    @ExceptionHandler(RefreshTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleRefreshTokenException(RefreshTokenException ex) {
        log.error(ex.getMessage());
        HttpStatus httpStatus = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(httpStatus)
                .body(ApiResponseUtil.createResponse(httpStatus.value(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        log.error("An error occurred: {}", ex.getMessage(), ex);
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(httpStatus)
                .body(ApiResponseUtil.createResponse(httpStatus.value(), "Invalid request body. Please check your JSON format or data types."));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKeyException(DuplicateKeyException ex) {
        log.error("An error occurred: {}", ex.getMessage(), ex);
        HttpStatus httpStatus = HttpStatus.CONFLICT;
        return ResponseEntity.status(httpStatus)
                             .body(ApiResponseUtil.createResponse(httpStatus.value(), ex.getMessage()));
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUsernameNotFoundException(UsernameNotFoundException ex) {
        log.error("An error occurred: {}", ex.getMessage(), ex);
        HttpStatus httpStatus = HttpStatus.NOT_FOUND;
        return ResponseEntity.status(httpStatus)
                             .body(ApiResponseUtil.createResponse(httpStatus.value(), ex.getMessage()));
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidPasswordException(InvalidPasswordException ex) {
        log.error("An error occurred: {}", ex.getMessage(), ex);
        HttpStatus httpStatus = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(httpStatus)
                             .body(ApiResponseUtil.createResponse(httpStatus.value(), ex.getMessage()));
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaymentFailedException(PaymentFailedException ex) {
        log.error("An error occurred: {}", ex.getMessage(), ex);
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(httpStatus)
                             .body(ApiResponseUtil.createResponse(httpStatus.value(), ex.getMessage()));
    }

    // 품절·판매 중 아님은 몰림 상황에서 대량으로 나오는 정상 거절이라 로그를 남기지 않는다.
    @ExceptionHandler({SoldOutException.class, NotOnSaleException.class})
    public ResponseEntity<ApiResponse<Void>> handleStockRejected(RuntimeException ex) {
        HttpStatus httpStatus = HttpStatus.CONFLICT;
        return ResponseEntity.status(httpStatus)
                             .body(ApiResponseUtil.createResponse(httpStatus.value(), ex.getMessage()));
    }

    @ExceptionHandler(StockUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleStockUnavailable(StockUnavailableException ex) {
        log.error("재고 판정 불가(Redis 장애 등): {}", ex.getMessage(), ex);
        HttpStatus httpStatus = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus)
                             .body(ApiResponseUtil.createResponse(httpStatus.value(), ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex) {
        log.error("An error occurred: {}", ex.getMessage(), ex);
        HttpStatus httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(httpStatus)
                .body(ApiResponseUtil.createResponse(httpStatus.value(), "server error"));
    }
}

