package com.yofaso.shop.config;

import com.yofaso.shop.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** JWT stateless, quyền lấy lại từ DB mỗi request; CORS chỉ theo danh sách cấu hình. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  /** BCrypt dùng chung khi đăng ký/đổi mật khẩu/bootstrap. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /** Khóa HMAC tối thiểu 256 bit, phải được cung cấp từ môi trường. */
  @Bean
  SecretKeySpec signingKey(@Value("${app.jwt.secret}") String secret) {
    Problem.require(
        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32
            && !secret.startsWith("CHANGE"),
        500,
        "JWT_CONFIG_INVALID",
        "JWT_SECRET cần ít nhất 32 byte ngẫu nhiên.");
    return new SecretKeySpec(
        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
  }

  /** Encoder JWT HS256, key chỉ nằm trên Backend. */
  @Bean
  JwtEncoder jwtEncoder(SecretKeySpec key) {
    return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
  }

  /** Kiểm tra chữ ký, thời hạn và issuer token. */
  @Bean
  JwtDecoder jwtDecoder(SecretKeySpec key) {
    var d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
      boolean valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
          && jwt.getSubject() != null && !jwt.getSubject().isBlank()
          && jwt.getAudience() != null && jwt.getAudience().contains("yofasoshop-api")
          && jwt.getClaim("authVersion") instanceof Number;
      return valid ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
          : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
              new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Missing required access token claims", null));
    };
    d.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer("yofasoshop"), requiredClaims));
    return d;
  }

  /** Mọi API private đều cần JWT; admin/staff còn được kiểm tra ở method. */
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      Db db,
      JsonMapper json,
      @Value("${app.cors.origins:}") String origins,
      @Value("${app.rate-limit:120}") int limit)
      throws Exception {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(origins.isBlank() ? List.of() : Arrays.asList(origins.split(",")));
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-ID"));
    cors.setExposedHeaders(
        List.of(
            "X-Correlation-ID",
            "Retry-After",
            "X-RateLimit-Limit",
            "X-RateLimit-Remaining",
            "X-RateLimit-Reset"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    http.csrf(c -> c.disable())
        .cors(c -> c.configurationSource(source))
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password",
                        "/actuator/health",
                        "/api/v1/contract",
                        "/api/v1/convert/address",
                        "/api/v1/convert/batch")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/catalog/**",
                        "/api/v1/promotions",
                        "/api/v1/provinces", "/api/v1/provinces/**",
                        "/api/v1/districts/**", "/api/v1/wards/**",
                        "/api/v1/new-provinces", "/api/v1/new-provinces/**", "/api/v1/new-wards/**",
                        "/api/v1/full-address", "/api/v1/new-full-address",
                        "/api/v1/search-address", "/api/v1/search-new-address", "/api/v1/suggest",
                        "/api/v1/profile", "/api/v1/merge-history/**",
                        "/api/v1/users/*/avatar")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(
                        j ->
                            j.jwtAuthenticationConverter(
                                jwt -> {
                                  var users =
                                      db.rows(
                                          "SELECT TrangThaiHoatDong,MatKhau FROM NguoiDung"
                                              + " WHERE MaNguoiDung=?",
                                          jwt.getSubject());
                                  if (users.isEmpty()) throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token");
                                  var user = users.getFirst();
                                  if (!"HOAT_DONG".equals(user.get("TrangThaiHoatDong"))
                                      || db.runtime().version(jwt.getSubject())
                                          != ((Number) jwt.getClaim("authVersion")).longValue()
                                      || !com.yofaso.shop.common.Idempotency.hash(Db.text(user,"MatKhau")).equals(jwt.getClaimAsString("credentialStamp")))
                                    throw new org.springframework.security.oauth2.core
                                        .OAuth2AuthenticationException("invalid_token");
                                  var roles =
                                      db
                                          .rows(
                                              "SELECT v.TenVaiTro FROM VaiTro v JOIN"
                                                  + " NguoiDung_VaiTro r ON r.MaVaiTro=v.MaVaiTro"
                                                  + " WHERE r.MaNguoiDung=?",
                                              jwt.getSubject())
                                          .stream()
                                          .map(
                                              r ->
                                                  new SimpleGrantedAuthority(
                                                      Db.text(r, "TenVaiTro")))
                                          .toList();
                                  return new JwtAuthenticationToken(jwt, roles, jwt.getSubject());
                                }))
                    .authenticationEntryPoint(
                        (q, r, e) ->
                            write(
                                json,
                                q,
                                r,
                                401,
                                "UNAUTHORIZED",
                                "Token thiếu, hết hạn hoặc không hợp lệ.")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> write(json, q, r, 401, "UNAUTHORIZED", "Vui lòng đăng nhập."))
                    .accessDeniedHandler(
                        (q, r, x) ->
                            write(json, q, r, 403, "FORBIDDEN", "Bạn không có quyền truy cập.")))
        .addFilterBefore(
            new RequestFilter(json, limit), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /** Ghi lỗi từ filter theo cùng cấu trúc controller. */
  static void write(
      JsonMapper json,
      HttpServletRequest req,
      HttpServletResponse res,
      int status,
      String code,
      String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            json.writeValueAsString(
                Errors.body(
                    code,
                    message,
                    Objects.toString(req.getAttribute("correlationId"), "unknown"),
                    Map.of(),
                    status)));
  }

  /** Limiter theo địa chỉ socket (không tin X-Forwarded-For), giới hạn RAM bằng expiry. */
  static class RequestFilter extends OncePerRequestFilter {
    record Bucket(long window, int used) {}

    final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    final JsonMapper json;
    final int limit;

    /** Khởi tạo một limiter cho một process; triển khai nhiều replica cần limiter chung. */
    RequestFilter(JsonMapper json, int limit) {
      this.json = json;
      this.limit = limit;
    }

    /** Gắn correlation ID, chặn burst và không cache dữ liệu người dùng. */
    @Override
    protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain chain)
        throws ServletException, IOException {
      String id = q.getHeader("X-Correlation-ID");
      if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) id = UUID.randomUUID().toString();
      q.setAttribute("correlationId", id);
      r.setHeader("X-Correlation-ID", id);
      r.setHeader("Cache-Control", "no-store");
      long window = System.currentTimeMillis() / 60000;
      if (buckets.size() > 10000) buckets.entrySet().removeIf(e -> e.getValue().window() < window);
      if (buckets.size() > 20000) {
        r.setHeader("Retry-After", "60");
        write(json, q, r, 429, "RATE_LIMITED", "Hệ thống đang bận.");
        return;
      }
      int cap = q.getRequestURI().contains("/auth/") ? Math.min(30, limit) : limit;
      Bucket b =
          buckets.compute(
              q.getRemoteAddr() + ":" + cap,
              (k, v) -> new Bucket(window, v == null || v.window() != window ? 1 : v.used() + 1));
      r.setHeader("X-RateLimit-Limit", "" + cap);
      r.setHeader("X-RateLimit-Remaining", "" + Math.max(0, cap - b.used()));
      r.setHeader("X-RateLimit-Reset", "" + ((window + 1) * 60));
      if (b.used() > cap) {
        r.setHeader(
            "Retry-After", "" + Math.max(1, (window + 1) * 60 - System.currentTimeMillis() / 1000));
        write(json, q, r, 429, "RATE_LIMITED", "Vui lòng đợi trước khi thử lại.");
        return;
      }
      org.slf4j.MDC.put("correlationId", id);
      try {
        chain.doFilter(q, r);
      } finally {
        org.slf4j.MDC.remove("correlationId");
      }
    }
  }
}
