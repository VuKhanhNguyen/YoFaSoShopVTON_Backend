package com.yofaso.shop.common;

import jakarta.validation.constraints.*;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** OpenAPI sinh từ route và record DTO thật, gồm request schema để Postman/SDK không lệch code. */
@RestController
public class ContractController {
  private final ApplicationContext context;

  /** Tra mapping sau khi context khởi động để không tạo vòng lặp bean. */
  public ContractController(ApplicationContext context) {
    this.context = context;
  }

  /** Hợp đồng máy đọc được cho API v1, không có token/config secret. */
  @GetMapping("/api/v1/contract")
  public Map<String, Object> contract() {
    var paths = new TreeMap<String, Object>();
    var schemas = new TreeMap<String, Object>();
    var mapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    mapping
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1")) continue;
                for (var method : info.getMethodsCondition().getMethods()) {
                  var operation = new LinkedHashMap<String, Object>();
                  String operationId =
                      handler.getBeanType().getSimpleName() + "_" + handler.getMethod().getName();
                  if (info.getPatternValues().size() > 1) operationId += "_" + path.replaceAll("[^A-Za-z0-9]", "_");
                  operation.put("operationId", operationId);
                  operation.put("summary", summary(handler));
                  var roles = handler.getMethodAnnotation(PreAuthorize.class);
                  if (roles != null) operation.put("x-authorization", roles.value());
                  boolean open =
                      handler.getBeanType().equals(com.yofaso.shop.location.LocationController.class)
                          || path.startsWith("/api/v1/catalog/") && method.name().equals("GET")
                          || path.equals("/api/v1/promotions") && method.name().equals("GET")
                          || path.equals("/api/v1/contract")
                          || Set.of(
                                  "/api/v1/auth/register",
                                  "/api/v1/auth/login",
                                  "/api/v1/auth/refresh",
                                  "/api/v1/auth/forgot-password",
                                  "/api/v1/auth/reset-password")
                              .contains(path);
                  operation.put(
                      "security", open ? List.of() : List.of(Map.of("bearerAuth", List.of())));
                  var params = new ArrayList<Map<String, Object>>();
                  var multipart = new TreeMap<String, Object>();
                  var requiredMultipart = new ArrayList<String>();
                  for (var p : handler.getMethod().getParameters()) {
                    if (p.isAnnotationPresent(RequestBody.class))
                      operation.put(
                          "requestBody",
                          Map.of(
                              "required",
                              true,
                              "content",
                              Map.of(
                                  "application/json",
                                  Map.of("schema", schema(p.getParameterizedType(), schemas)))));
                    String in = null, name = p.getName();
                    boolean required = true;
                    var ps = schema(p.getParameterizedType(), schemas);
                    if (p.isAnnotationPresent(PathVariable.class)) {
                      in = "path";
                      String v = p.getAnnotation(PathVariable.class).value();
                      if (!v.isEmpty()) name = v;
                    }
                    if (p.isAnnotationPresent(RequestHeader.class)) {
                      in = "header";
                      var v = p.getAnnotation(RequestHeader.class);
                      if (!v.value().isEmpty()) name = v.value();
                      required = v.required();
                    }
                    if (p.isAnnotationPresent(RequestParam.class)) {
                      in = "query";
                      var v = p.getAnnotation(RequestParam.class);
                      if (!v.value().isEmpty()) name = v.value();
                      required =
                          v.required()
                              && v.defaultValue()
                                  .equals(
                                      org.springframework.web.bind.annotation.ValueConstants
                                          .DEFAULT_NONE);
                      if (!v.defaultValue()
                          .equals(
                              org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE))
                        ps.put("default", parseDefault(v.defaultValue(), p.getType()));
                    }
                    if (info.getConsumesCondition().getConsumableMediaTypes().stream()
                            .anyMatch(m -> m.toString().startsWith("multipart/"))
                        && in != null) {
                      multipart.put(
                          name,
                          MultipartFile.class.isAssignableFrom(p.getType())
                              ? Map.of("type", "string", "format", "binary")
                              : ps);
                      if (required) requiredMultipart.add(name);
                    } else if (in != null)
                      params.add(
                          Map.of("name", name, "in", in, "required", required, "schema", ps));
                  }
                  if (!multipart.isEmpty())
                    operation.put(
                        "requestBody",
                        Map.of(
                            "required",
                            true,
                            "content",
                            Map.of(
                                "multipart/form-data",
                                Map.of(
                                    "schema",
                                    Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        multipart,
                                        "required",
                                        requiredMultipart)))));
                  if (!params.isEmpty()) operation.put("parameters", params);
                  var status = handler.getMethodAnnotation(ResponseStatus.class);
                  String success =
                      status == null
                          ? "200"
                          : Integer.toString(
                              (status.code() == HttpStatus.INTERNAL_SERVER_ERROR
                                      ? status.value()
                                      : status.code())
                                  .value());
                  boolean binary =
                      handler.getMethod().getGenericReturnType().getTypeName().contains("byte[]");
                  operation.put(
                      "responses",
                      Map.of(
                          success,
                          Map.of(
                              "description",
                              "Thành công; xem hướng dẫn theo nghiệp vụ.",
                              "content",
                              binary
                                  ? Map.of(
                                      "image/png",
                                      Map.of(
                                          "schema", Map.of("type", "string", "format", "binary")))
                                  : Map.of(
                                      "application/json",
                                      Map.of(
                                          "schema",
                                          schema(
                                              handler.getMethod().getGenericReturnType(),
                                              schemas)))),
                          "default",
                          Map.of(
                              "description",
                              "Lỗi có mã ổn định",
                              "content",
                              Map.of(
                                  "application/json",
                                  Map.of(
                                      "schema",
                                      Map.of("$ref", "#/components/schemas/ErrorBody"))))));
                  @SuppressWarnings("unchecked")
                  var methods =
                      (Map<String, Object>)
                          paths.computeIfAbsent(path, k -> new TreeMap<String, Object>());
                  methods.put(method.name().toLowerCase(Locale.ROOT), operation);
                }
              }
            });
    schema(Errors.ErrorBody.class, schemas);
    return Map.of(
        "openapi",
        "3.1.0",
        "info",
        Map.of(
            "title",
            "YoFaSoShop Backend API",
            "version",
            "1.0.0",
            "description",
            "API nghiệp vụ; AI có contract riêng. response map dùng alias camelCase theo hướng dẫn;"
                + " không có endpoint thanh toán online giả."),
        "servers",
        List.of(Map.of("url", "http://localhost:8080")),
        "paths",
        paths,
        "components",
        Map.of(
            "schemas",
            schemas,
            "securitySchemes",
            Map.of(
                "bearerAuth", Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"))));
  }

  /** Đọc comment Java của method để summary hữu ích, fallback về tên khi source không đóng gói. */
  private static String summary(HandlerMethod h) {
    return h.getBeanType().getSimpleName() + ": " + h.getMethod().getName();
  }

  /** Default query có kiểu đúng trong OpenAPI. */
  private static Object parseDefault(String value, Class<?> type) {
    if (type == int.class) return Integer.valueOf(value);
    if (type == boolean.class) return Boolean.valueOf(value);
    if (type == java.math.BigDecimal.class) return new java.math.BigDecimal(value);
    return value;
  }

  /**
   * Sinh schema request record + constraint; response map linh hoạt không giả thành DTO cố định.
   */
  private static Map<String, Object> schema(Type type, Map<String, Object> definitions) {
    if (type instanceof ParameterizedType p) {
      if (p.getRawType() == List.class)
        return new LinkedHashMap<>(
            Map.of("type", "array", "items", schema(p.getActualTypeArguments()[0], definitions)));
      return new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", true));
    }
    if (!(type instanceof Class<?> c)) return new LinkedHashMap<>(Map.of("type", "object"));
    if (c == String.class) return new LinkedHashMap<>(Map.of("type", "string"));
    if (c == int.class || c == Integer.class || c == long.class)
      return new LinkedHashMap<>(Map.of("type", "integer"));
    if (c == boolean.class || c == Boolean.class)
      return new LinkedHashMap<>(Map.of("type", "boolean"));
    if (c == java.math.BigDecimal.class) return new LinkedHashMap<>(Map.of("type", "number"));
    if (c == java.time.LocalDateTime.class || c == java.time.Instant.class)
      return new LinkedHashMap<>(Map.of("type", "string", "format", "date-time"));
    if (c.isRecord()) {
      String name = c.getSimpleName();
      if (!definitions.containsKey(name)) {
        definitions.put(name, new LinkedHashMap<>());
        var properties = new TreeMap<String, Object>();
        var required = new ArrayList<String>();
        for (var f : c.getRecordComponents()) {
          var fs =
              schema(
                  f.getGenericType(),
                  definitions); // Record validation annotations are propagated to accessor/field.
          try {
            var field = c.getDeclaredField(f.getName());
            if (field.isAnnotationPresent(NotNull.class)
                || field.isAnnotationPresent(NotBlank.class)
                || field.isAnnotationPresent(NotEmpty.class)
                || f.getType().isPrimitive()) required.add(f.getName());
            var size = field.getAnnotation(Size.class);
            if (size != null) {
              boolean array = f.getType() == List.class;
              fs.put(array ? "minItems" : "minLength", size.min());
              fs.put(array ? "maxItems" : "maxLength", size.max());
            }
            var pattern = field.getAnnotation(Pattern.class);
            if (pattern != null) fs.put("pattern", "^(?:" + pattern.regexp() + ")$");
            if (field.isAnnotationPresent(AssertTrue.class)) fs.put("const", true);
            var min = field.getAnnotation(Min.class);
            if (min != null) fs.put("minimum", min.value());
            var max = field.getAnnotation(Max.class);
            if (max != null) fs.put("maximum", max.value());
            var decimal = field.getAnnotation(DecimalMin.class);
            if (decimal != null) fs.put("minimum", new java.math.BigDecimal(decimal.value()));
            if (field.isAnnotationPresent(Email.class)) fs.put("format", "email");
          } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
          }
          properties.put(f.getName(), fs);
        }
        if (c == Errors.ErrorBody.class) {
          required.clear();
          required.addAll(properties.keySet());
        }
        definitions.put(
            name,
            Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false));
      }
      return new LinkedHashMap<>(Map.of("$ref", "#/components/schemas/" + name));
    }
    return new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", true));
  }
}
