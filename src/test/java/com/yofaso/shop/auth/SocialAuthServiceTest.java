package com.yofaso.shop.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.yofaso.shop.common.Problem;
import org.junit.jupiter.api.Test;

/** Kiểm tra thuần logic OAuth2 (username auto-gen, PKCE, provider validation, SSRF avatar allowlist, email normalization). */
class SocialAuthServiceTest {

  @Test
  void buildUsernameBaseExtractsEmailPrefixAndSanitizes() {
    assertEquals("abc", SocialAuthService.buildUsernameBase("abc@gmail.com", "Nguyễn Văn A", "GOOGLE"));
    assertEquals("nguyenvana", SocialAuthService.buildUsernameBase("nguyen.van.a+1@gmail.com", "Nguyễn Văn A", "GOOGLE"));
    assertEquals("u12345abc", SocialAuthService.buildUsernameBase("12345abc@gmail.com", "", "GOOGLE"));
    assertEquals("nguyenvukh", SocialAuthService.buildUsernameBase(null, "Nguyễn Vũ Khanh", "FACEBOOK"));
    assertEquals("duongminhd", SocialAuthService.buildUsernameBase(null, "Đường Minh Đức", "FACEBOOK"));
    assertEquals("fb", SocialAuthService.buildUsernameBase(null, "   ", "FACEBOOK"));
    assertEquals("gg", SocialAuthService.buildUsernameBase("", "", "GOOGLE"));
  }

  @Test
  void normalizeProviderRejectsUnknownValues() {
    assertEquals("GOOGLE", SocialAuthService.normalizeProvider("google"));
    assertEquals("GOOGLE", SocialAuthService.normalizeProvider(" Google "));
    assertEquals("FACEBOOK", SocialAuthService.normalizeProvider("facebook"));
    assertThrows(Problem.class, () -> SocialAuthService.normalizeProvider("apple"));
    assertThrows(Problem.class, () -> SocialAuthService.normalizeProvider(""));
  }

  @Test
  void pkceChallengeUsesSha256Base64UrlWithoutPadding() {
    String challenge = SocialAuthService.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge);
  }

  @Test
  void trustedAvatarUrlBlocksNonHttpsOrUntrustedHosts() {
    assertTrue(SocialAuthService.isTrustedAvatarUrl("https://lh3.googleusercontent.com/a/ACg8ocK123"));
    assertTrue(SocialAuthService.isTrustedAvatarUrl("https://platform-lookaside.fbsbx.com/platform/profilepic/?asid=123"));
    assertTrue(SocialAuthService.isTrustedAvatarUrl("https://scontent.fdad3-1.fna.fbcdn.net/v/t39.jpg"));
    assertFalse(SocialAuthService.isTrustedAvatarUrl("http://lh3.googleusercontent.com/a/123"));
    assertFalse(SocialAuthService.isTrustedAvatarUrl("http://127.0.0.1:8080/internal"));
    assertFalse(SocialAuthService.isTrustedAvatarUrl("https://evil.example.com/avatar.png"));
    assertFalse(SocialAuthService.isTrustedAvatarUrl("https://user:pass@lh3.googleusercontent.com/a/123"));
  }

  @Test
  void emailNormalizationKeepsGmailDotsDistinctPerProjectRule() {
    assertNotEquals(
        PasswordResetService.email("abc@gmail.com"),
        PasswordResetService.email("a.bc@gmail.com"));
    assertEquals(
        PasswordResetService.email("Abc@Gmail.COM "),
        PasswordResetService.email("abc@gmail.com"));
  }
}
