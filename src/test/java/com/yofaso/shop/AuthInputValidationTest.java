package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import com.yofaso.shop.auth.AuthController;
import com.yofaso.shop.auth.AuthService;
import com.yofaso.shop.common.Problem;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** Kiểm tra ràng buộc mới mà không khởi động Spring hoặc kết nối database. */
class AuthInputValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test void usernamesMustStartWithLetterAndContainOnlyAlphanumerics() {
    for (String username : new String[] {"1khanh", "khanh_test", "khanh-test", "Khánh", "abc", "abcdefghijklmnopq", "khanh test"}) {
      assertFalse(validator.validate(new AuthController.Register(username, "TestingPassword123", "Khanh", "0901234567", "khanh@example.test")).isEmpty(), username);
    }
    for (String username : new String[] {"Khanh", "Khanh09112004", "abcdefghijklmnop"}) {
      assertTrue(validator.validate(new AuthController.Register(username, "TestingPassword123", "Khanh", "0901234567", "khanh@example.test")).isEmpty(), username);
    }
    // Login cho phép username, email hoặc số điện thoại (từ 3 đến 100 ký tự)
    assertTrue(validator.validate(new AuthController.Login("0901234567", "TestingPassword123")).isEmpty());
    assertTrue(validator.validate(new AuthController.Login("khanh@example.test", "TestingPassword123")).isEmpty());
    assertFalse(validator.validate(new AuthController.Login("ab", "TestingPassword123")).isEmpty());
    assertFalse(validator.validate(new AuthController.Login("a".repeat(101), "TestingPassword123")).isEmpty());
  }

  @Test void passwordRequiresLettersNumbersAndBcryptByteLimit() {
    assertDoesNotThrow(() -> AuthService.validatePassword("Nvk_09112004"));
    for (String password : new String[] {"short123", "abcdefghijk", "12345678901", "ấ".repeat(25) + "1"})
      assertThrows(Problem.class, () -> AuthService.validatePassword(password));
  }
}
