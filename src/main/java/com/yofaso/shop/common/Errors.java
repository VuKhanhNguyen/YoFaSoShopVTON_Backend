package com.yofaso.shop.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Một dạng JSON lỗi dùng chung controller và Spring Security. */
@RestControllerAdvice
public class Errors {
  /** DTO lỗi tương thích cách FastAPI báo lỗi. */
  public record ErrorBody(
      String errorCode,
      String message,
      Map<String, Object> details,
      String correlationId,
      boolean retryable) {}

  /** Tạo body không lộ input thô, stacktrace hoặc SQL. */
  public static ErrorBody body(
      String code, String message, String id, Map<String, Object> details, int status) {
    return new ErrorBody(
        code,
        message,
        details,
        id,
        status == 429 || status == 502 || status == 503 || status == 504);
  }

  /** Ánh xạ lỗi domain, validation, quyền, trùng dữ liệu và framework. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorBody> handle(Exception e, HttpServletRequest req) {
    int status = 500;
    String code = "INTERNAL_ERROR", message = "Có lỗi nội bộ; tra cứu theo correlationId.";
    Map<String, Object> details = Map.of();
    if (e instanceof Problem p) {
      status = p.status;
      code = p.code;
      message = p.getMessage();
    } else if (e instanceof MethodArgumentNotValidException v) {
      status = 422;
      code = "REQUEST_INVALID";
      message = "Kiểm tra các trường nhập.";
      details =
          Map.of(
              "fields",
              v.getBindingResult().getFieldErrors().stream()
                  .map(
                      f ->
                          Map.of(
                              "field",
                              f.getField(),
                              "message",
                              Objects.toString(f.getDefaultMessage(), "Không hợp lệ")))
                  .toList());
    } else if (e instanceof DataIntegrityViolationException) {
      status = 409;
      code = "DATA_CONFLICT";
      message = "Dữ liệu bị trùng hoặc còn liên kết với tài nguyên khác.";
    } else if (e instanceof AccessDeniedException) {
      status = 403;
      code = "FORBIDDEN";
      message = "Bạn không có quyền thực hiện thao tác này.";
    } else if (e instanceof MaxUploadSizeExceededException) {
      status = 413;
      code = "UPLOAD_TOO_LARGE";
      message = "File vượt dung lượng cho phép.";
    } else if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
      status = 400;
      code = "REQUEST_INVALID";
      message = "JSON sai kiểu, có trường lạ hoặc sai cú pháp.";
    } else if (e instanceof jakarta.validation.ConstraintViolationException
        || e instanceof org.springframework.web.method.annotation.HandlerMethodValidationException) {
      status = 422;
      code = "REQUEST_INVALID";
      message = "Tham số vượt giới hạn hoặc sai định dạng.";
    } else if (e instanceof org.springframework.web.ErrorResponse w) {
      status = w.getStatusCode().value();
      code = "HTTP_" + status;
      message = "Request không hợp lệ hoặc không tìm thấy tài nguyên.";
    }
    if (status == 500)
      org.slf4j.LoggerFactory.getLogger(Errors.class)
          .error("request_failed correlationId={}", req.getAttribute("correlationId"), e);
    return ResponseEntity.status(status)
        .body(
            body(
                code,
                message,
                Objects.toString(req.getAttribute("correlationId"), "unknown"),
                details,
                status));
  }
}
