package com.yofaso.shop.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Quên mật khẩu cho mọi vai trò, bằng chứng sở hữu email là OTP. */
@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {
  public record Forgot(@NotBlank @Email @Size(max=100) String email) {}
  public record Reset(@NotBlank @Pattern(regexp="[a-f0-9]{32}") String requestId,
      @NotBlank @Email @Size(max=100) String email,@NotBlank @Pattern(regexp="[0-9]{6}") String otp,
      @NotBlank @Size(min=10,max=72) String newPassword) {}
  private final PasswordResetService service;
  public PasswordResetController(PasswordResetService service) {this.service=service;}
  /** Gửi mã 6 số qua SMTP, response không chứa OTP và không xác nhận email tồn tại. */
  @PostMapping("/forgot-password") @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String,Object> forgot(@Valid @RequestBody Forgot b) {return service.request(b.email());}
  /** Xác minh mã còn hạn, đổi BCrypt password rồi thu hồi token cũ; không tự đăng nhập. */
  @PostMapping("/reset-password")
  public Map<String,Object> reset(@Valid @RequestBody Reset b) {return service.reset(b);}
}
