package com.yofaso.shop.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Endpoint OAuth2 Google & Facebook cho Mobile App / Expo Web và quản lý liên kết tài khoản cá nhân. */
@RestController
@RequestMapping("/api/v1")
public class SocialAuthController {
  public record ExchangeRequest(@NotBlank @Size(max = 128) String code) {}

  public record ConfirmLinkRequest(
      @NotBlank @Size(max = 128) String linkToken,
      @NotBlank @Pattern(regexp = "CONFIRM|CANCEL") String action) {}

  public record StartLinkRequest(@Size(max = 500) String redirectUri) {}

  private final SocialAuthService social;

  public SocialAuthController(SocialAuthService social) {
    this.social = social;
  }

  /** Kiểm tra nhà cung cấp OAuth2 nào đã được cấu hình key trên server. */
  @GetMapping("/auth/oauth/providers")
  public Map<String, Object> providers() {
    return social.providersStatus();
  }

  /** Bước 1: Chuyển hướng sang màn hình xác thực Google hoặc Facebook (dùng chung cho cả Login và Register). */
  @GetMapping("/auth/oauth/{provider}/start")
  public ResponseEntity<Void> start(
      @PathVariable String provider,
      @RequestParam(required = false) String redirectUri) {
    String url = social.buildAuthorizationUrl(provider, redirectUri, "login", null);
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
  }

  /** Bước 2: Nhận callback từ Google/Facebook, xác minh token/profile và redirect kèm mã một lần về App/Web. */
  @GetMapping("/auth/oauth/{provider}/callback")
  public ResponseEntity<Void> callback(
      @PathVariable String provider,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      @RequestParam(name = "error_description", required = false) String errorDescription) {
    var result = social.handleCallback(provider, code, state, error, errorDescription);
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(result.location())).build();
  }

  /** Bước 3: App/Web đổi mã một lần (TTL 90s) lấy JWT YoFaSo hoặc nhận yêu cầu xác nhận liên kết trùng email. */
  @PostMapping("/auth/oauth/exchange")
  public Map<String, Object> exchange(@Valid @RequestBody ExchangeRequest body) {
    return social.exchangeOneTimeCode(body.code());
  }

  /** Bước 4 (khi trùng email): Xác nhận hợp nhất liên kết Google/Facebook vào tài khoản hiện có hoặc hủy bỏ. */
  @PostMapping("/auth/oauth/confirm-link")
  public Map<String, Object> confirmLink(@Valid @RequestBody ConfirmLinkRequest body) {
    return social.confirmAccountLink(body.linkToken(), body.action());
  }

  /** Xem các liên kết Google/Facebook hiện tại của người dùng đang đăng nhập. */
  @GetMapping("/me/linked-accounts")
  public Map<String, Object> linkedAccounts(Authentication a) {
    return social.linkedAccountsOverview(a.getName());
  }

  /** Khởi tạo luồng liên kết thêm Google hoặc Facebook từ màn hình Cài đặt (không đưa JWT lên URL). */
  @PostMapping("/me/linked-accounts/{provider}/start")
  public Map<String, Object> startLink(
      Authentication a,
      @PathVariable String provider,
      @Valid @RequestBody(required = false) StartLinkRequest body) {
    String redirectUri = body == null ? null : body.redirectUri();
    String authorizeUrl = social.buildAuthorizationUrl(provider, redirectUri, "link", a.getName());
    return Map.of("authorizeUrl", authorizeUrl);
  }

  /** Hủy liên kết Google hoặc Facebook trong màn hình Cài đặt. */
  @DeleteMapping("/me/linked-accounts/{provider}")
  public Map<String, Object> unlink(Authentication a, @PathVariable String provider) {
    return social.unlinkProvider(a.getName(), provider);
  }
}
