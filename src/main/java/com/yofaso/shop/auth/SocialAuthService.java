package com.yofaso.shop.auth;

import com.yofaso.shop.ai.MediaService;
import com.yofaso.shop.ai.R2Storage;
import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.common.RuntimeState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Luồng OAuth2 Broker (Google + PKCE & Facebook) cho khách hàng trên Mobile/Expo Web.
 * Giữ client secret hoàn toàn trên Backend; state/code/linkToken dùng một lần với TTL ngắn trong RuntimeState.
 */
@Service
public class SocialAuthService {
  private static final Logger log = LoggerFactory.getLogger(SocialAuthService.class);
  private static final long STATE_TTL_MS = 10 * 60_000L;
  private static final long CODE_TTL_MS = 90_000L;
  private static final long LINK_TOKEN_TTL_MS = 5 * 60_000L;
  private static final int MAX_AVATAR_BYTES = 5 * 1024 * 1024;
  private static final SecureRandom RANDOM = new SecureRandom();

  public record ExternalProfile(
      String provider,
      String externalId,
      String email,
      boolean emailVerified,
      String fullName,
      String pictureUrl) {}

  public record CallbackRedirect(String location) {}

  private final Db db;
  private final AuthService auth;
  private final PasswordEncoder passwords;
  private final R2Storage r2;
  private final MediaService media;
  private final JsonMapper json;
  private final TransactionTemplate tx;
  private final HttpClient http;

  private final String publicBaseUrl;
  private final String mobileWebUrl;
  private final List<String> allowedRedirectPrefixes;

  private final String googleClientId;
  private final String googleClientSecret;
  private final String googleAuthUrl;
  private final String googleTokenUrl;
  private final String googleUserInfoUrl;

  private final String facebookAppId;
  private final String facebookAppSecret;
  private final String facebookAuthUrl;
  private final String facebookTokenUrl;
  private final String facebookUserInfoUrl;

  public SocialAuthService(
      Db db,
      AuthService auth,
      PasswordEncoder passwords,
      R2Storage r2,
      MediaService media,
      JsonMapper json,
      TransactionTemplate tx,
      @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl,
      @Value("${app.mobile-web-url:http://localhost:8081}") String mobileWebUrl,
      @Value("${app.oauth.allowed-redirect-prefixes:yofasomobile://,exp://,http://localhost:8081,http://127.0.0.1:8081,http://localhost:3000}")
          String allowedRedirectPrefixes,
      @Value("${app.oauth.google.client-id:}") String googleClientId,
      @Value("${app.oauth.google.client-secret:}") String googleClientSecret,
      @Value("${app.oauth.google.auth-url:https://accounts.google.com/o/oauth2/v2/auth}") String googleAuthUrl,
      @Value("${app.oauth.google.token-url:https://oauth2.googleapis.com/token}") String googleTokenUrl,
      @Value("${app.oauth.google.userinfo-url:https://openidconnect.googleapis.com/v1/userinfo}") String googleUserInfoUrl,
      @Value("${app.oauth.facebook.app-id:}") String facebookAppId,
      @Value("${app.oauth.facebook.app-secret:}") String facebookAppSecret,
      @Value("${app.oauth.facebook.auth-url:https://www.facebook.com/v21.0/dialog/oauth}") String facebookAuthUrl,
      @Value("${app.oauth.facebook.token-url:https://graph.facebook.com/v21.0/oauth/access_token}") String facebookTokenUrl,
      @Value("${app.oauth.facebook.userinfo-url:https://graph.facebook.com/v21.0/me}") String facebookUserInfoUrl) {
    this.db = db;
    this.auth = auth;
    this.passwords = passwords;
    this.r2 = r2;
    this.media = media;
    this.json = json;
    this.tx = tx;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(12))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    this.publicBaseUrl = trimSlash(publicBaseUrl);
    this.mobileWebUrl = trimSlash(mobileWebUrl);
    this.allowedRedirectPrefixes = parseAllowedPrefixes(allowedRedirectPrefixes, this.mobileWebUrl);
    this.googleClientId = safeTrim(googleClientId);
    this.googleClientSecret = safeTrim(googleClientSecret);
    this.googleAuthUrl = safeTrim(googleAuthUrl);
    this.googleTokenUrl = safeTrim(googleTokenUrl);
    this.googleUserInfoUrl = safeTrim(googleUserInfoUrl);
    this.facebookAppId = safeTrim(facebookAppId);
    this.facebookAppSecret = safeTrim(facebookAppSecret);
    this.facebookAuthUrl = safeTrim(facebookAuthUrl);
    this.facebookTokenUrl = safeTrim(facebookTokenUrl);
    this.facebookUserInfoUrl = safeTrim(facebookUserInfoUrl);
  }

  /** Trả danh sách nhà cung cấp đã cấu hình đủ key (không lộ secret). */
  public Map<String, Object> providersStatus() {
    return Map.of(
        "google", isConfigured("GOOGLE"),
        "facebook", isConfigured("FACEBOOK"));
  }

  public boolean isConfigured(String provider) {
    return switch (provider) {
      case "GOOGLE" -> !googleClientId.isEmpty() && !googleClientSecret.isEmpty();
      case "FACEBOOK" -> !facebookAppId.isEmpty() && !facebookAppSecret.isEmpty();
      default -> false;
    };
  }

  /** Khởi tạo URL ủy quyền Google/Facebook cho đăng nhập hoặc liên kết tài khoản từ Settings. */
  public String buildAuthorizationUrl(String rawProvider, String redirectUri, String mode, String linkUserId) {
    String provider = normalizeProvider(rawProvider);
    String safeRedirect = validateClientRedirectUri(redirectUri);
    if (!isConfigured(provider)) {
      String label = providerLabel(provider);
      return appendQuery(
          safeRedirect,
          Map.of(
              "oauthError", "OAUTH_NOT_CONFIGURED",
              "oauthMessage", "Đăng nhập bằng " + label + " chưa được cấu hình key trên máy chủ."));
    }

    String state = randomToken(32);
    String verifier = randomToken(32);
    long expiresAt = System.currentTimeMillis() + STATE_TTL_MS;

    storeState(state, provider, verifier, safeRedirect, mode, linkUserId, expiresAt);

    String callbackUri = callbackUri(provider);
    if ("GOOGLE".equals(provider)) {
      String challenge = pkceChallenge(verifier);
      Map<String, String> params = new LinkedHashMap<>();
      params.put("client_id", googleClientId);
      params.put("redirect_uri", callbackUri);
      params.put("response_type", "code");
      params.put("scope", "openid email profile");
      params.put("state", state);
      params.put("code_challenge", challenge);
      params.put("code_challenge_method", "S256");
      params.put("prompt", "select_account");
      return appendQuery(googleAuthUrl, params);
    }

    Map<String, String> params = new LinkedHashMap<>();
    params.put("client_id", facebookAppId);
    params.put("redirect_uri", callbackUri);
    params.put("response_type", "code");
    params.put("scope", "public_profile,email");
    params.put("state", state);
    return appendQuery(facebookAuthUrl, params);
  }

  /** Nhận callback từ Google/Facebook, xác minh state/code/profile rồi cấp mã 1 lần quay về App/Web. */
  public CallbackRedirect handleCallback(
      String rawProvider, String code, String state, String error, String errorDescription) {
    String provider = normalizeProvider(rawProvider);
    Map<String, Object> savedState = consumeState(state);
    if (savedState == null) {
      String fallback = defaultWebAuthRedirect();
      return new CallbackRedirect(
          appendQuery(
              fallback,
              Map.of(
                  "oauthError", "OAUTH_STATE_INVALID",
                  "oauthMessage", "Phiên đăng nhập đã hết hạn hoặc không hợp lệ. Vui lòng thử lại.")));
    }

    String redirectUri = Db.text(savedState, "redirectUri");
    String savedProvider = Db.text(savedState, "provider");
    String verifier = Db.text(savedState, "verifier");
    String mode = Db.text(savedState, "mode");
    String linkUserId = Db.text(savedState, "linkUserId");

    if (!provider.equals(savedProvider)) {
      return errorRedirect(redirectUri, "OAUTH_STATE_MISMATCH", "Trạng thái xác thực không khớp nhà cung cấp.");
    }
    if (error != null && !error.isBlank()) {
      boolean cancelled = "access_denied".equalsIgnoreCase(error) || "user_denied".equalsIgnoreCase(error);
      return errorRedirect(
          redirectUri,
          cancelled ? "OAUTH_CANCELLED" : "OAUTH_PROVIDER_ERROR",
          cancelled
              ? "Bạn đã hủy đăng nhập bằng " + providerLabel(provider) + "."
              : "Không thể xác thực với " + providerLabel(provider) + ". Vui lòng thử lại.");
    }
    if (code == null || code.isBlank()) {
      return errorRedirect(redirectUri, "OAUTH_CODE_MISSING", "Thiếu mã xác thực từ " + providerLabel(provider) + ".");
    }

    ExternalProfile profile;
    try {
      profile = fetchExternalProfile(provider, code, verifier, callbackUri(provider));
    } catch (Problem p) {
      log.warn("oauth_provider_failed provider={} code={}", provider, p.code);
      return errorRedirect(redirectUri, p.code, p.getMessage());
    } catch (Exception e) {
      log.warn("oauth_provider_error provider={} err={}", provider, e.getMessage());
      return errorRedirect(
          redirectUri,
          "OAUTH_EXCHANGE_FAILED",
          "Không thể kết nối tới " + providerLabel(provider) + ". Vui lòng thử lại sau.");
    }

    try {
      Map<String, Object> outcome =
          "link".equals(mode) && !linkUserId.isBlank()
              ? resolveExplicitLink(linkUserId, profile)
              : resolveLoginOrCreate(profile);

      String oneTimeCode = issueOneTimeCode(outcome);
      return new CallbackRedirect(appendQuery(redirectUri, Map.of("oauthCode", oneTimeCode)));
    } catch (Problem p) {
      return errorRedirect(redirectUri, p.code, p.getMessage());
    } catch (Exception e) {
      log.error("oauth_callback_db_error provider={}", provider, e);
      return errorRedirect(
          redirectUri,
          "OAUTH_INTERNAL_ERROR",
          "Không thể hoàn tất đăng nhập. Vui lòng thử lại.");
    }
  }

  /** Đổi mã một lần (TTL 90s) từ callback lấy JWT hoặc yêu cầu xác nhận liên kết tài khoản. */
  public Map<String, Object> exchangeOneTimeCode(String code) {
    Map<String, Object> row = consumeOneTimeCode(code);
    Problem.require(row != null, 400, "OAUTH_CODE_INVALID", "Mã xác thực OAuth không hợp lệ hoặc đã hết hạn.");

    String type = Db.text(row, "type");
    if ("LOGIN".equals(type)) {
      String userId = Db.text(row, "userId");
      var result = new LinkedHashMap<String, Object>(auth.issueTokens(userId));
      result.put("status", "AUTHENTICATED");
      result.put("provider", Db.text(row, "provider"));
      result.put("isNewUser", Boolean.TRUE.equals(row.get("isNewUser")));
      return result;
    }
    if ("LINK_REQUIRED".equals(type)) {
      var result = new LinkedHashMap<String, Object>();
      result.put("status", "LINK_REQUIRED");
      result.put("linkToken", Db.text(row, "linkToken"));
      result.put("provider", Db.text(row, "provider"));
      result.put("email", Db.text(row, "email"));
      result.put("existingFullName", Db.text(row, "existingFullName"));
      result.put("existingProviders", row.getOrDefault("existingProviders", List.of()));
      result.put("message", Db.text(row, "message"));
      return result;
    }
    if ("LINKED".equals(type)) {
      String userId = Db.text(row, "userId");
      var result = new LinkedHashMap<String, Object>();
      result.put("status", "LINKED");
      result.put("provider", Db.text(row, "provider"));
      result.put("linkedAccounts", linkedAccountsForUser(userId));
      return result;
    }
    throw new Problem(400, "OAUTH_CODE_INVALID", "Mã xác thực không hợp lệ.");
  }

  /**
   * Xác nhận hoặc hủy liên kết khi phát hiện email trùng giữa 2 kênh (VD: đã có Google/mật khẩu, nay đăng nhập Facebook).
   */
  public Map<String, Object> confirmAccountLink(String linkToken, String action) {
    Map<String, Object> pending = consumeLinkToken(linkToken);
    Problem.require(
        pending != null,
        400,
        "OAUTH_LINK_EXPIRED",
        "Phiên xác nhận liên kết đã hết hạn. Vui lòng bấm đăng nhập lại.");

    if ("CANCEL".equalsIgnoreCase(action)) {
      return Map.of("status", "CANCELLED", "cancelled", true);
    }
    Problem.require(
        "CONFIRM".equalsIgnoreCase(action),
        400,
        "ACTION_INVALID",
        "Hành động xác nhận không hợp lệ.");

    String targetUserId = Db.text(pending, "targetUserId");
    String provider = Db.text(pending, "provider");
    String externalId = Db.text(pending, "externalId");
    String pictureUrl = Db.text(pending, "pictureUrl");

    String resolvedUserId =
        tx.execute(
            status -> {
              var user =
                  db.one(
                      "SELECT MaNguoiDung, TrangThaiHoatDong, AnhDaiDien FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",
                      targetUserId);
              Problem.require(
                  "HOAT_DONG".equals(user.get("TrangThaiHoatDong")),
                  403,
                  "ACCOUNT_LOCKED",
                  "Tài khoản đang bị tạm khóa.");
              assertCustomerOnly(targetUserId);

              var existingByExternal =
                  db.rows(
                      "SELECT MaNguoiDung FROM LienKetDangNhap WHERE NhaCungCap=? AND MaDinhDanhNgoai=?",
                      provider,
                      externalId);
              if (!existingByExternal.isEmpty()) {
                String owner = Db.text(existingByExternal.getFirst(), "MaNguoiDung");
                Problem.require(
                    targetUserId.equals(owner),
                    409,
                    "OAUTH_ALREADY_LINKED_OTHER_USER",
                    "Tài khoản " + providerLabel(provider) + " này đã liên kết với người dùng khác.");
                return targetUserId;
              }

              var existingSameProvider =
                  db.rows(
                      "SELECT MaDinhDanhNgoai FROM LienKetDangNhap WHERE MaNguoiDung=? AND NhaCungCap=?",
                      targetUserId,
                      provider);
              Problem.require(
                  existingSameProvider.isEmpty(),
                  409,
                  "OAUTH_PROVIDER_CONFLICT",
                  "Tài khoản của bạn đã liên kết với một tài khoản " + providerLabel(provider) + " khác.");

              db.exec(
                  "INSERT INTO LienKetDangNhap(MaLienKet, MaNguoiDung, NhaCungCap, MaDinhDanhNgoai) VALUES(?,?,?,?)",
                  Db.id("LK"),
                  targetUserId,
                  provider,
                  externalId);
              db.audit(targetUserId, "OAUTH_ACCOUNT_LINKED", provider);
              return targetUserId;
            });

    // Best-effort đồng bộ avatar nếu tài khoản cũ chưa có ảnh đại diện
    if (pictureUrl != null && !pictureUrl.isBlank()) {
      trySyncAvatarIfEmpty(resolvedUserId, pictureUrl);
    }

    var result = new LinkedHashMap<String, Object>(auth.issueTokens(resolvedUserId));
    result.put("status", "AUTHENTICATED");
    result.put("linked", true);
    result.put("provider", provider);
    return result;
  }

  /** Danh sách nhà cung cấp đã liên kết của user đang đăng nhập. */
  public Map<String, Object> linkedAccountsOverview(String userId) {
    var user = db.one("SELECT Email, SoDienThoai FROM NguoiDung WHERE MaNguoiDung=?", userId);
    var links = linkedAccountsForUser(userId);
    return Map.of(
        "linkedAccounts", links,
        "hasEmail", !Db.text(user, "Email").isBlank(),
        "hasPhone", !Db.text(user, "SoDienThoai").isBlank(),
        "providersConfigured", providersStatus());
  }

  public List<Map<String, Object>> linkedAccountsForUser(String userId) {
    return db.rows(
        "SELECT MaLienKet AS linkId, NhaCungCap AS provider, NgayLienKet AS linkedAt"
            + " FROM LienKetDangNhap WHERE MaNguoiDung=? ORDER BY NgayLienKet",
        userId);
  }

  /** Hủy liên kết Google hoặc Facebook trong màn hình Cài đặt, có khóa an toàn tránh khóa chết tài khoản. */
  public Map<String, Object> unlinkProvider(String userId, String rawProvider) {
    String provider = normalizeProvider(rawProvider);
    return tx.execute(
        status -> {
          var user =
              db.one(
                  "SELECT MaNguoiDung, Email FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",
                  userId);
          var links =
              db.rows(
                  "SELECT MaLienKet, NhaCungCap FROM LienKetDangNhap WHERE MaNguoiDung=?",
                  userId);
          boolean hasTarget = links.stream().anyMatch(r -> provider.equals(Db.text(r, "NhaCungCap")));
          Problem.require(
              hasTarget,
              404,
              "OAUTH_LINK_NOT_FOUND",
              "Tài khoản chưa liên kết với " + providerLabel(provider) + ".");

          boolean hasEmail = !Db.text(user, "Email").isBlank();
          Problem.require(
              hasEmail || links.size() > 1,
              409,
              "OAUTH_UNLINK_LAST_METHOD",
              "Vui lòng bổ sung địa chỉ email trong hồ sơ trước khi hủy liên kết phương thức đăng nhập duy nhất.");

          db.exec(
              "DELETE FROM LienKetDangNhap WHERE MaNguoiDung=? AND NhaCungCap=?",
              userId,
              provider);
          db.audit(userId, "OAUTH_ACCOUNT_UNLINKED", provider);
          return Map.of(
              "unlinked", true,
              "provider", provider,
              "linkedAccounts", linkedAccountsForUser(userId));
        });
  }

  // ==================== CORE RESOLUTION (DB TRANSACTIONS) ====================

  private Map<String, Object> resolveExplicitLink(String userId, ExternalProfile profile) {
    return tx.execute(
        status -> {
          var user =
              db.one(
                  "SELECT MaNguoiDung, TrangThaiHoatDong FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",
                  userId);
          Problem.require(
              "HOAT_DONG".equals(user.get("TrangThaiHoatDong")),
              403,
              "ACCOUNT_LOCKED",
              "Tài khoản đang bị tạm khóa.");
          assertCustomerOnly(userId);

          var byExt =
              db.rows(
                  "SELECT MaNguoiDung FROM LienKetDangNhap WHERE NhaCungCap=? AND MaDinhDanhNgoai=?",
                  profile.provider(),
                  profile.externalId());
          if (!byExt.isEmpty()) {
            String existingOwner = Db.text(byExt.getFirst(), "MaNguoiDung");
            Problem.require(
                userId.equals(existingOwner),
                409,
                "OAUTH_ALREADY_LINKED_OTHER_USER",
                "Tài khoản " + providerLabel(profile.provider()) + " này đã được liên kết với người dùng khác.");
            return RuntimeState.row("type", "LINKED", "userId", userId, "provider", profile.provider());
          }

          var byProvider =
              db.rows(
                  "SELECT MaLienKet FROM LienKetDangNhap WHERE MaNguoiDung=? AND NhaCungCap=?",
                  userId,
                  profile.provider());
          Problem.require(
              byProvider.isEmpty(),
              409,
              "OAUTH_PROVIDER_CONFLICT",
              "Bạn đã liên kết một tài khoản " + providerLabel(profile.provider()) + " khác. Hãy hủy liên kết cũ trước.");

          db.exec(
              "INSERT INTO LienKetDangNhap(MaLienKet, MaNguoiDung, NhaCungCap, MaDinhDanhNgoai) VALUES(?,?,?,?)",
              Db.id("LK"),
              userId,
              profile.provider(),
              profile.externalId());
          db.audit(userId, "OAUTH_ACCOUNT_LINKED", profile.provider());
          return RuntimeState.row("type", "LINKED", "userId", userId, "provider", profile.provider());
        });
  }

  private Map<String, Object> resolveLoginOrCreate(ExternalProfile profile) {
    // Chuẩn hóa email giữ nguyên dấu chấm & +tag (đồng bộ PasswordResetService.email; sau này chỉ sửa đúng 1 hàm nếu muốn chuẩn hóa Gmail).
    String normalizedEmail =
        (profile.email() == null || profile.email().isBlank())
            ? null
            : PasswordResetService.email(profile.email());

    Map<String, Object> outcome =
        tx.execute(
            status -> {
              // 1. Đã có liên kết (NhaCungCap, MaDinhDanhNgoai) -> Đăng nhập thẳng
              var existingLinks =
                  db.rows(
                      "SELECT MaNguoiDung FROM LienKetDangNhap WHERE NhaCungCap=? AND MaDinhDanhNgoai=?",
                      profile.provider(),
                      profile.externalId());
              if (!existingLinks.isEmpty()) {
                String uid = Db.text(existingLinks.getFirst(), "MaNguoiDung");
                var u = db.one("SELECT TrangThaiHoatDong FROM NguoiDung WHERE MaNguoiDung=?", uid);
                Problem.require(
                    "HOAT_DONG".equals(u.get("TrangThaiHoatDong")),
                    403,
                    "ACCOUNT_LOCKED",
                    "Tài khoản đang bị tạm khóa. Vui lòng liên hệ cửa hàng để được hỗ trợ.");
                assertCustomerOnly(uid);
                db.audit(uid, "OAUTH_LOGIN", profile.provider());
                return RuntimeState.row(
                    "type", "LOGIN",
                    "userId", uid,
                    "provider", profile.provider(),
                    "isNewUser", false);
              }

              // 2. Chưa có liên kết nhưng trùng Email với NguoiDung sẵn có -> Luồng xác nhận liên kết an toàn
              if (normalizedEmail != null) {
                db.one("SELECT MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_CUSTOMER' FOR UPDATE");
                var matches =
                    db.rows(
                        "SELECT MaNguoiDung, HoVaTen, TrangThaiHoatDong FROM NguoiDung WHERE LOWER(TRIM(Email))=? FOR UPDATE",
                        normalizedEmail);
                if (!matches.isEmpty()) {
                  var matched = matches.getFirst();
                  String existingUid = Db.text(matched, "MaNguoiDung");
                  assertCustomerOnly(existingUid);
                  Problem.require(
                      "HOAT_DONG".equals(matched.get("TrangThaiHoatDong")),
                      403,
                      "ACCOUNT_LOCKED",
                      "Tài khoản đang bị tạm khóa. Vui lòng liên hệ cửa hàng để được hỗ trợ.");

                  var sameProviderLinks =
                      db.rows(
                          "SELECT MaDinhDanhNgoai FROM LienKetDangNhap WHERE MaNguoiDung=? AND NhaCungCap=?",
                          existingUid,
                          profile.provider());
                  Problem.require(
                      sameProviderLinks.isEmpty(),
                      409,
                      "OAUTH_PROVIDER_CONFLICT",
                      "Email " + normalizedEmail + " đã liên kết với một tài khoản " + providerLabel(profile.provider()) + " khác.");

                  if ("GOOGLE".equals(profile.provider())) {
                    Problem.require(
                        profile.emailVerified(),
                        403,
                        "OAUTH_EMAIL_UNVERIFIED",
                        "Email Google của bạn chưa được xác minh nên không thể đối chiếu với tài khoản hiện có.");
                  }

                  List<String> existingProviders =
                      db.rows(
                              "SELECT NhaCungCap FROM LienKetDangNhap WHERE MaNguoiDung=? ORDER BY NgayLienKet",
                              existingUid)
                          .stream()
                          .map(r -> Db.text(r, "NhaCungCap"))
                          .toList();

                  String linkToken =
                      storeLinkToken(
                          existingUid,
                          profile.provider(),
                          profile.externalId(),
                          normalizedEmail,
                          profile.fullName(),
                          profile.pictureUrl());

                  String existingMethodText =
                      existingProviders.isEmpty()
                          ? "mật khẩu YoFaSo"
                          : String.join(
                              " & ",
                              existingProviders.stream().map(SocialAuthService::providerLabel).toList());
                  String message =
                      "Email "
                          + normalizedEmail
                          + " đã được sử dụng cho tài khoản "
                          + Db.text(matched, "HoVaTen")
                          + " ("
                          + existingMethodText
                          + ").";

                  return RuntimeState.row(
                      "type", "LINK_REQUIRED",
                      "linkToken", linkToken,
                      "provider", profile.provider(),
                      "email", normalizedEmail,
                      "existingFullName", Db.text(matched, "HoVaTen"),
                      "existingProviders", existingProviders,
                      "message", message);
                }
              }

              // 3. Chưa tồn tại -> Tạo tài khoản khách hàng mới (SoDienThoai = NULL, username & password tự sinh)
              String userId = Db.id("ND");
              String username = generateUniqueUsername(normalizedEmail, profile.fullName(), profile.provider());
              String fullName = sanitizeFullName(profile.fullName(), username);
              String randomSecret = randomToken(32);

              db.exec(
                  "INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai,Email,GioiTinh)"
                      + " VALUES(?,?,?,?,NULL,?,?)",
                  userId,
                  username,
                  passwords.encode(randomSecret),
                  fullName,
                  normalizedEmail,
                  "KHAC");
              Problem.require(
                  db.exec(
                          "INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro)"
                              + " SELECT ?,MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_CUSTOMER'",
                          userId)
                      == 1,
                  503,
                  "ROLE_CONFIG_INVALID",
                  "Hệ thống chưa thiết lập vai trò khách hàng chuẩn.");
              db.exec(
                  "INSERT INTO LienKetDangNhap(MaLienKet, MaNguoiDung, NhaCungCap, MaDinhDanhNgoai) VALUES(?,?,?,?)",
                  Db.id("LK"),
                  userId,
                  profile.provider(),
                  profile.externalId());
              auth.grantWelcomeVouchers(userId);
              db.audit(userId, "OAUTH_REGISTER", profile.provider());

              return RuntimeState.row(
                  "type", "LOGIN",
                  "userId", userId,
                  "provider", profile.provider(),
                  "isNewUser", true);
            });

    // Sau khi commit DB: đồng bộ ảnh đại diện Google/Facebook lên R2 (best-effort, không chặn đăng nhập)
    if ("LOGIN".equals(Db.text(outcome, "type"))
        && Boolean.TRUE.equals(outcome.get("isNewUser"))
        && profile.pictureUrl() != null
        && !profile.pictureUrl().isBlank()) {
      trySyncAvatarIfEmpty(Db.text(outcome, "userId"), profile.pictureUrl());
    }
    return outcome;
  }

  private void assertCustomerOnly(String userId) {
    var roles = auth.roles(userId);
    boolean isStaff =
        roles.stream().anyMatch(r -> "ROLE_SHOP_ADMIN".equals(r) || r.startsWith("ROLE_STAFF_"));
    Problem.require(
        !isStaff && roles.contains("ROLE_CUSTOMER"),
        403,
        "OAUTH_STAFF_ACCOUNT_FORBIDDEN",
        "Tài khoản nhân sự nội bộ không hỗ trợ đăng nhập qua Google/Facebook. Vui lòng sử dụng cổng nội bộ.");
  }

  // ==================== PROVIDER HTTP EXCHANGE ====================

  @SuppressWarnings("unchecked")
  private ExternalProfile fetchExternalProfile(
      String provider, String code, String verifier, String callbackUri) throws Exception {
    if ("GOOGLE".equals(provider)) {
      String form =
          formBody(
              Map.of(
                  "grant_type", "authorization_code",
                  "code", code,
                  "redirect_uri", callbackUri,
                  "client_id", googleClientId,
                  "client_secret", googleClientSecret,
                  "code_verifier", verifier));
      var tokenReq =
          HttpRequest.newBuilder(URI.create(googleTokenUrl))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build();
      var tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
      Problem.require(
          tokenRes.statusCode() == 200,
          502,
          "OAUTH_TOKEN_FAILED",
          "Không thể trao đổi mã xác thực với Google.");
      Map<String, Object> tokenJson = json.readValue(tokenRes.body(), Map.class);
      String accessToken = Objects.toString(tokenJson.get("access_token"), "");
      Problem.require(!accessToken.isBlank(), 502, "OAUTH_TOKEN_FAILED", "Google không trả về access token.");

      var infoReq =
          HttpRequest.newBuilder(URI.create(googleUserInfoUrl))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + accessToken)
              .header("Accept", "application/json")
              .GET()
              .build();
      var infoRes = http.send(infoReq, HttpResponse.BodyHandlers.ofString());
      Problem.require(
          infoRes.statusCode() == 200,
          502,
          "OAUTH_PROFILE_FAILED",
          "Không thể lấy thông tin hồ sơ Google.");
      Map<String, Object> info = json.readValue(infoRes.body(), Map.class);
      String sub = Objects.toString(info.get("sub"), "").trim();
      Problem.require(!sub.isEmpty(), 502, "OAUTH_PROFILE_FAILED", "Hồ sơ Google thiếu mã định danh.");
      String email = info.get("email") == null ? null : info.get("email").toString().trim();
      boolean verified = Boolean.TRUE.equals(info.get("email_verified"))
          || "true".equalsIgnoreCase(Objects.toString(info.get("email_verified"), ""));
      String name = Objects.toString(info.get("name"), "").trim();
      String picture = info.get("picture") == null ? null : info.get("picture").toString().trim();
      return new ExternalProfile("GOOGLE", sub, email, verified, name, picture);
    }

    // FACEBOOK
    String form =
        formBody(
            Map.of(
                "client_id", facebookAppId,
                "client_secret", facebookAppSecret,
                "redirect_uri", callbackUri,
                "code", code));
    var tokenReq =
        HttpRequest.newBuilder(URI.create(facebookTokenUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    var tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
    Problem.require(
        tokenRes.statusCode() == 200,
        502,
        "OAUTH_TOKEN_FAILED",
        "Không thể trao đổi mã xác thực với Facebook.");
    Map<String, Object> tokenJson = json.readValue(tokenRes.body(), Map.class);
    String accessToken = Objects.toString(tokenJson.get("access_token"), "");
    Problem.require(!accessToken.isBlank(), 502, "OAUTH_TOKEN_FAILED", "Facebook không trả về access token.");

    String meUrl = appendQuery(facebookUserInfoUrl, Map.of("fields", "id,name,email,picture.type(large)"));
    var infoReq =
        HttpRequest.newBuilder(URI.create(meUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET()
            .build();
    var infoRes = http.send(infoReq, HttpResponse.BodyHandlers.ofString());
    Problem.require(
        infoRes.statusCode() == 200,
        502,
        "OAUTH_PROFILE_FAILED",
        "Không thể lấy thông tin hồ sơ Facebook.");
    Map<String, Object> info = json.readValue(infoRes.body(), Map.class);
    String fbId = Objects.toString(info.get("id"), "").trim();
    Problem.require(!fbId.isEmpty(), 502, "OAUTH_PROFILE_FAILED", "Hồ sơ Facebook thiếu mã định danh.");
    String email = info.get("email") == null ? null : info.get("email").toString().trim();
    String name = Objects.toString(info.get("name"), "").trim();
    String picture = extractFacebookPictureUrl(info);
    return new ExternalProfile("FACEBOOK", fbId, email, email != null && !email.isBlank(), name, picture);
  }

  @SuppressWarnings("unchecked")
  private static String extractFacebookPictureUrl(Map<String, Object> info) {
    Object pic = info.get("picture");
    if (!(pic instanceof Map<?, ?> picMap)) return null;
    Object data = picMap.get("data");
    if (!(data instanceof Map<?, ?> dataMap)) return null;
    if (Boolean.TRUE.equals(dataMap.get("is_silhouette"))) return null;
    Object url = dataMap.get("url");
    return url == null ? null : url.toString().trim();
  }

  // ==================== AVATAR SYNC (BEST-EFFORT, SSRF-GUARDED) ====================

  private void trySyncAvatarIfEmpty(String userId, String pictureUrl) {
    if (!r2.isConfigured()) return;
    try {
      var u = db.one("SELECT AnhDaiDien FROM NguoiDung WHERE MaNguoiDung=?", userId);
      if (!Db.text(u, "AnhDaiDien").isBlank()) return;
      if (!isTrustedAvatarUrl(pictureUrl)) return;

      var req =
          HttpRequest.newBuilder(URI.create(pictureUrl))
              .timeout(Duration.ofSeconds(8))
              .GET()
              .build();
      var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() != 200) return;
      byte[] raw = res.body();
      if (raw == null || raw.length == 0 || raw.length > MAX_AVATAR_BYTES) return;

      var img = ImageIO.read(new ByteArrayInputStream(raw));
      if (img == null || img.getWidth() < 32 || img.getHeight() < 32) return;
      var out = new ByteArrayOutputStream();
      ImageIO.write(img, "png", out);
      byte[] pngBytes = out.toByteArray();

      String key = "avatars/" + userId + "/avatar-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".png";
      r2.upload(key, pngBytes);
      media.saveToCache(key, pngBytes);
      db.exec("UPDATE NguoiDung SET AnhDaiDien=? WHERE MaNguoiDung=? AND (AnhDaiDien IS NULL OR AnhDaiDien='')", key, userId);
    } catch (Exception e) {
      log.debug("oauth_avatar_sync_skipped userId={} reason={}", userId, e.getMessage());
    }
  }

  static boolean isTrustedAvatarUrl(String url) {
    try {
      URI uri = URI.create(url);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
        return false;
      }
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      return host.endsWith(".googleusercontent.com")
          || host.equals("lh3.googleusercontent.com")
          || host.endsWith(".fbcdn.net")
          || host.equals("graph.facebook.com")
          || host.endsWith(".fbsbx.com");
    } catch (Exception e) {
      return false;
    }
  }

  // ==================== USERNAME & NAME HELPERS ====================

  /**
   * Sinh chuỗi gốc cho TenDangNhap: ưu tiên phần trước @ của email, fallback HoVaTen không dấu, fallback gg/fb.
   * Luôn bắt đầu bằng chữ cái Latin, chỉ gồm [a-z0-9], tối đa 10 ký tự để chừa chỗ gắn hậu tố chống trùng.
   */
  public static String buildUsernameBase(String email, String fullName, String provider) {
    String raw = "";
    if (email != null && email.contains("@")) {
      raw = email.substring(0, email.indexOf('@'));
    } else if (fullName != null && !fullName.isBlank()) {
      raw = removeDiacritics(fullName);
    }
    String clean = removeDiacritics(raw).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    if (clean.isEmpty()) {
      clean = "FACEBOOK".equalsIgnoreCase(provider) ? "fb" : "gg";
    }
    if (Character.isDigit(clean.charAt(0))) {
      clean = "u" + clean;
    }
    return clean.length() > 10 ? clean.substring(0, 10) : clean;
  }

  String generateUniqueUsername(String email, String fullName, String provider) {
    String base = buildUsernameBase(email, fullName, provider);
    if (base.length() >= 4 && !usernameExists(base)) {
      return base;
    }
    String prefix = base.length() > 10 ? base.substring(0, 10) : base;
    for (int i = 0; i < 40; i++) {
      String candidate = prefix + (1000 + RANDOM.nextInt(9000));
      if (!usernameExists(candidate)) {
        return candidate;
      }
    }
    return "u" + Long.toString(Math.abs(RANDOM.nextLong()), 36).substring(0, 10);
  }

  private boolean usernameExists(String username) {
    return db.count("SELECT COUNT(*) FROM NguoiDung WHERE TenDangNhap=?", username) > 0;
  }

  static String sanitizeFullName(String fullName, String fallbackUsername) {
    if (fullName == null || fullName.isBlank()) return fallbackUsername;
    String trimmed = fullName.trim().replaceAll("\\s+", " ");
    if (trimmed.length() > 100) trimmed = trimmed.substring(0, 100).trim();
    return trimmed.isEmpty() ? fallbackUsername : trimmed;
  }

  private static String removeDiacritics(String input) {
    if (input == null) return "";
    String replaced = input.replace('đ', 'd').replace('Đ', 'D');
    String normalized = Normalizer.normalize(replaced, Normalizer.Form.NFD);
    return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
  }

  // ==================== RUNTIME STATE (ATOMIC SINGLE-USE TOKENS) ====================

  private synchronized void storeState(
      String state,
      String provider,
      String verifier,
      String redirectUri,
      String mode,
      String linkUserId,
      long expiresAt) {
    purgeExpired("oauthstate");
    db.runtime()
        .put(
            "oauthstate",
            state,
            RuntimeState.row(
                "state", state,
                "provider", provider,
                "verifier", verifier,
                "redirectUri", redirectUri,
                "mode", mode == null ? "login" : mode,
                "linkUserId", linkUserId == null ? "" : linkUserId,
                "expiresAt", expiresAt));
  }

  private synchronized Map<String, Object> consumeState(String state) {
    if (state == null || state.isBlank()) return null;
    var row = db.runtime().findById("oauthstate", state);
    if (row == null) return null;
    db.runtime().remove("oauthstate", state);
    long expiresAt = ((Number) row.getOrDefault("expiresAt", 0L)).longValue();
    return expiresAt > System.currentTimeMillis() ? row : null;
  }

  private synchronized String issueOneTimeCode(Map<String, Object> payload) {
    purgeExpired("oauthcode");
    String code = randomToken(32);
    var copy = new LinkedHashMap<String, Object>(payload);
    copy.put("code", code);
    copy.put("expiresAt", System.currentTimeMillis() + CODE_TTL_MS);
    db.runtime().put("oauthcode", code, copy);
    return code;
  }

  private synchronized Map<String, Object> consumeOneTimeCode(String code) {
    if (code == null || code.isBlank()) return null;
    var row = db.runtime().findById("oauthcode", code);
    if (row == null) return null;
    db.runtime().remove("oauthcode", code);
    long expiresAt = ((Number) row.getOrDefault("expiresAt", 0L)).longValue();
    return expiresAt > System.currentTimeMillis() ? row : null;
  }

  private synchronized String storeLinkToken(
      String targetUserId,
      String provider,
      String externalId,
      String email,
      String fullName,
      String pictureUrl) {
    purgeExpired("oauthlink");
    String token = randomToken(32);
    db.runtime()
        .put(
            "oauthlink",
            token,
            RuntimeState.row(
                "linkToken", token,
                "targetUserId", targetUserId,
                "provider", provider,
                "externalId", externalId,
                "email", email == null ? "" : email,
                "fullName", fullName == null ? "" : fullName,
                "pictureUrl", pictureUrl == null ? "" : pictureUrl,
                "expiresAt", System.currentTimeMillis() + LINK_TOKEN_TTL_MS));
    return token;
  }

  private synchronized Map<String, Object> consumeLinkToken(String linkToken) {
    if (linkToken == null || linkToken.isBlank()) return null;
    var row = db.runtime().findById("oauthlink", linkToken);
    if (row == null) return null;
    db.runtime().remove("oauthlink", linkToken);
    long expiresAt = ((Number) row.getOrDefault("expiresAt", 0L)).longValue();
    return expiresAt > System.currentTimeMillis() ? row : null;
  }

  private void purgeExpired(String collection) {
    long now = System.currentTimeMillis();
    String idField =
        switch (collection) {
          case "oauthstate" -> "state";
          case "oauthcode" -> "code";
          case "oauthlink" -> "linkToken";
          default -> "id";
        };
    for (var old :
        db.runtime()
            .find(
                collection,
                r -> ((Number) r.getOrDefault("expiresAt", 0L)).longValue() <= now)) {
      String id = Db.text(old, idField);
      if (!id.isBlank()) db.runtime().remove(collection, id);
    }
  }

  // ==================== VALIDATION & URL HELPERS ====================

  public static String normalizeProvider(String raw) {
    Problem.require(raw != null && !raw.isBlank(), 400, "OAUTH_PROVIDER_INVALID", "Thiếu nhà cung cấp đăng nhập.");
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    Problem.require(
        "GOOGLE".equals(upper) || "FACEBOOK".equals(upper),
        400,
        "OAUTH_PROVIDER_INVALID",
        "Chỉ hỗ trợ đăng nhập bằng Google hoặc Facebook.");
    return upper;
  }

  public static String providerLabel(String provider) {
    return "GOOGLE".equalsIgnoreCase(provider) ? "Google" : "Facebook";
  }

  String validateClientRedirectUri(String redirectUri) {
    String uri = (redirectUri == null || redirectUri.isBlank()) ? defaultWebAuthRedirect() : redirectUri.trim();
    Problem.require(
        uri.length() <= 500 && !uri.contains("\r") && !uri.contains("\n") && !uri.contains(" "),
        400,
        "OAUTH_REDIRECT_INVALID",
        "Redirect URI không hợp lệ.");
    String lower = uri.toLowerCase(Locale.ROOT);
    Problem.require(
        !lower.startsWith("javascript:") && !lower.startsWith("data:"),
        400,
        "OAUTH_REDIRECT_INVALID",
        "Redirect URI không được phép.");
    boolean allowed = allowedRedirectPrefixes.stream().anyMatch(p -> lower.startsWith(p.toLowerCase(Locale.ROOT)));
    Problem.require(
        allowed,
        400,
        "OAUTH_REDIRECT_INVALID",
        "Redirect URI không nằm trong danh sách ứng dụng được phép.");
    return uri;
  }

  private String callbackUri(String provider) {
    return publicBaseUrl + "/api/v1/auth/oauth/" + provider.toLowerCase(Locale.ROOT) + "/callback";
  }

  private String defaultWebAuthRedirect() {
    return (mobileWebUrl.isEmpty() ? "http://localhost:8081" : mobileWebUrl) + "/auth";
  }

  private static CallbackRedirect errorRedirect(String redirectUri, String code, String message) {
    return new CallbackRedirect(
        appendQuery(
            redirectUri,
            Map.of(
                "oauthError", code,
                "oauthMessage", message == null ? "Đăng nhập thất bại." : message)));
  }

  private static List<String> parseAllowedPrefixes(String csv, String mobileWebUrl) {
    var list = new ArrayList<String>();
    if (csv != null) {
      for (String p : csv.split(",")) {
        String clean = p.trim();
        if (!clean.isEmpty()) list.add(clean);
      }
    }
    if (mobileWebUrl != null && !mobileWebUrl.isBlank() && !list.contains(mobileWebUrl)) {
      list.add(mobileWebUrl);
    }
    if (list.stream().noneMatch(p -> p.equalsIgnoreCase("yofasomobile://"))) {
      list.add("yofasomobile://");
    }
    if (list.stream().noneMatch(p -> p.equalsIgnoreCase("exp://"))) {
      list.add("exp://");
    }
    return List.copyOf(list);
  }

  private static String randomToken(int numBytes) {
    byte[] buf = new byte[numBytes];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  public static String pkceChallenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String formBody(Map<String, String> params) {
    var sb = new StringBuilder();
    for (var e : params.entrySet()) {
      if (!sb.isEmpty()) sb.append('&');
      sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
      sb.append('=');
      sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  private static String appendQuery(String baseUrl, Map<String, String> params) {
    var sb = new StringBuilder(baseUrl);
    boolean hasQuery = baseUrl.contains("?");
    for (var e : params.entrySet()) {
      sb.append(hasQuery ? '&' : '?');
      hasQuery = true;
      sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
      sb.append('=');
      sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  private static String trimSlash(String value) {
    return value == null ? "" : value.trim().replaceAll("/+$", "");
  }

  private static String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }
}
