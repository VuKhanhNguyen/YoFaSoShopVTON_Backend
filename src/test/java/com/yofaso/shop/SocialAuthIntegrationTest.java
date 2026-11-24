package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.yofaso.shop.common.Db;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Kiểm thử đầu-cuối OAuth2 Google/Facebook trên HTTP + MySQL test riêng với stub Google/Facebook thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SocialAuthIntegrationTest {
  @LocalServerPort int port;
  @Autowired Db db;
  @Autowired PasswordEncoder passwords;
  @Autowired JsonMapper json;

  static HttpServer oauthStub;
  static volatile String nextGoogleSub = "gg-sub-1001";
  static volatile String nextGoogleEmail = "oauth.khanh@gmail.com";
  static volatile String nextGoogleName = "Nguyễn Vũ Khanh";
  static volatile String nextFacebookId = "fb-id-2001";
  static volatile String nextFacebookEmail = "oauth.khanh@gmail.com";
  static volatile String nextFacebookName = "Khanh Facebook";

  final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  String googleUserId;
  String googleAccessToken;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) throws Exception {
    oauthStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    oauthStub.createContext(
        "/google/token",
        x -> {
          String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(body.contains("code_verifier="), "Google token exchange must send PKCE code_verifier");
          respondJson(x, 200, "{\"access_token\":\"stub-gg-token\",\"token_type\":\"Bearer\"}");
        });
    oauthStub.createContext(
        "/google/userinfo",
        x -> {
          assertEquals("Bearer stub-gg-token", x.getRequestHeaders().getFirst("Authorization"));
          String emailField = nextGoogleEmail == null ? "null" : "\"" + nextGoogleEmail + "\"";
          respondJson(
              x,
              200,
              "{\"sub\":\""
                  + nextGoogleSub
                  + "\",\"email\":"
                  + emailField
                  + ",\"email_verified\":true,\"name\":\""
                  + nextGoogleName
                  + "\"}");
        });
    oauthStub.createContext(
        "/facebook/token",
        x -> respondJson(x, 200, "{\"access_token\":\"stub-fb-token\",\"token_type\":\"bearer\"}"));
    oauthStub.createContext(
        "/facebook/me",
        x -> {
          assertEquals("Bearer stub-fb-token", x.getRequestHeaders().getFirst("Authorization"));
          String emailPart =
              nextFacebookEmail == null ? "" : ",\"email\":\"" + nextFacebookEmail + "\"";
          respondJson(
              x,
              200,
              "{\"id\":\"" + nextFacebookId + "\",\"name\":\"" + nextFacebookName + "\"" + emailPart + "}");
        });
    oauthStub.start();

    String base = "http://127.0.0.1:" + oauthStub.getAddress().getPort();
    r.add("app.public-base-url", () -> "https://api.yofaso.id.vn");
    r.add("app.oauth.google.client-id", () -> "test-google-client-id");
    r.add("app.oauth.google.client-secret", () -> "test-google-client-secret");
    r.add("app.oauth.google.auth-url", () -> base + "/google/auth");
    r.add("app.oauth.google.token-url", () -> base + "/google/token");
    r.add("app.oauth.google.userinfo-url", () -> base + "/google/userinfo");
    r.add("app.oauth.facebook.app-id", () -> "test-facebook-app-id");
    r.add("app.oauth.facebook.app-secret", () -> "test-facebook-app-secret");
    r.add("app.oauth.facebook.auth-url", () -> base + "/facebook/auth");
    r.add("app.oauth.facebook.token-url", () -> base + "/facebook/token");
    r.add("app.oauth.facebook.userinfo-url", () -> base + "/facebook/me");
    r.add("app.ai.enabled", () -> false);
    r.add("app.rate-limit", () -> 100000);
    var registry = Files.createTempDirectory(Path.of("target"), "oauth-runtime-");
    r.add("app.runtime-root", registry::toString);
    r.add("app.storage-root", () -> "./data/test-media/" + UUID.randomUUID());
  }

  private static void respondJson(com.sun.net.httpserver.HttpExchange x, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(status, bytes.length);
    x.getResponseBody().write(bytes);
    x.close();
  }

  @AfterAll
  void tearDown() {
    if (oauthStub != null) oauthStub.stop(0);
  }

  @Test
  @Order(1)
  void googleFirstLoginCreatesNullablePhoneCustomerAndLink() throws Exception {
    var providers = getJson("/auth/oauth/providers", null, 200);
    assertEquals(true, providers.get("google"));
    assertEquals(true, providers.get("facebook"));

    // 1. Open redirect attempt must be blocked
    var badStart =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/auth/oauth/google/start?redirectUri=https://evil.example/steal"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, badStart.statusCode());

    // 2. Valid Google start -> extract state -> callback -> one-time oauthCode -> exchange
    String oauthCode = runOAuthBrowserFlow("google", "yofasomobile://auth-callback");
    var exchanged = postJson("/auth/oauth/exchange", null, Map.of("code", oauthCode), 200);
    assertEquals("AUTHENTICATED", exchanged.get("status"));
    assertEquals("GOOGLE", exchanged.get("provider"));
    assertEquals(true, exchanged.get("isNewUser"));
    assertNotNull(exchanged.get("accessToken"));

    @SuppressWarnings("unchecked")
    Map<String, Object> user = (Map<String, Object>) exchanged.get("user");
    googleUserId = user.get("userId").toString();
    googleAccessToken = exchanged.get("accessToken").toString();
    assertNull(user.get("phone"), "Tài khoản đăng ký bằng Google có SoDienThoai NULL");
    assertEquals("oauth.khanh@gmail.com", user.get("email"));
    assertEquals(List.of("GOOGLE"), user.get("linkedProviders"));

    // Replay of the same one-time code must fail
    postJson("/auth/oauth/exchange", null, Map.of("code", oauthCode), 400);
  }

  @Test
  @Order(2)
  void facebookWithSameEmailPromptsLinkAndMergesIntoSameAccount() throws Exception {
    String oauthCode = runOAuthBrowserFlow("facebook", "http://localhost:8081/auth");
    var exchanged = postJson("/auth/oauth/exchange", null, Map.of("code", oauthCode), 200);
    assertEquals("LINK_REQUIRED", exchanged.get("status"));
    assertEquals("FACEBOOK", exchanged.get("provider"));
    assertEquals("oauth.khanh@gmail.com", exchanged.get("email"));
    assertEquals(List.of("GOOGLE"), exchanged.get("existingProviders"));

    String linkToken = exchanged.get("linkToken").toString();
    var confirmed =
        postJson(
            "/auth/oauth/confirm-link",
            null,
            Map.of("linkToken", linkToken, "action", "CONFIRM"),
            200);
    assertEquals("AUTHENTICATED", confirmed.get("status"));
    assertEquals(true, confirmed.get("linked"));
    @SuppressWarnings("unchecked")
    Map<String, Object> user = (Map<String, Object>) confirmed.get("user");
    assertEquals(googleUserId, user.get("userId"), "Phải gộp vào đúng MaNguoiDung cũ");
    @SuppressWarnings("unchecked")
    List<String> linked = (List<String>) user.get("linkedProviders");
    assertTrue(linked.containsAll(List.of("GOOGLE", "FACEBOOK")));

    // Subsequent Facebook login signs straight into the merged user without prompting
    String secondCode = runOAuthBrowserFlow("facebook", "yofasomobile://auth-callback");
    var direct = postJson("/auth/oauth/exchange", null, Map.of("code", secondCode), 200);
    assertEquals("AUTHENTICATED", direct.get("status"));
    assertEquals(false, direct.get("isNewUser"));
  }

  @Test
  @Order(3)
  void gmailDotVariantStaysDistinctAndAddressAutoSyncsPhone() throws Exception {
    // Dot variant `oauthkhanh@gmail.com` != `oauth.khanh@gmail.com` -> creates separate user
    nextGoogleSub = "gg-sub-dot-1002";
    nextGoogleEmail = "oauthkhanh@gmail.com";
    nextGoogleName = "Khanh Dot Variant";
    String dotCode = runOAuthBrowserFlow("google", "yofasomobile://auth-callback");
    var dotRes = postJson("/auth/oauth/exchange", null, Map.of("code", dotCode), 200);
    assertEquals("AUTHENTICATED", dotRes.get("status"));
    assertEquals(true, dotRes.get("isNewUser"));
    @SuppressWarnings("unchecked")
    Map<String, Object> dotUser = (Map<String, Object>) dotRes.get("user");
    assertNotEquals(googleUserId, dotUser.get("userId"));

    // Social user adds first delivery address -> NguoiDung.SoDienThoai auto-syncs!
    postJson(
        "/me/addresses",
        googleAccessToken,
        Map.of(
            "recipientName", "Nguyễn Vũ Khanh",
            "phone", "0912345999",
            "street", "48 Cao Thắng",
            "ward", "Hải Châu",
            "city", "Đà Nẵng",
            "defaultAddress", true,
            "provinceCode", "48",
            "wardCode", "20242"),
        201);
    var refreshedMe = getJson("/me", googleAccessToken, 200);
    assertEquals("0912345999", refreshedMe.get("phone"));
  }

  @Test
  @Order(4)
  void facebookPhoneOnlyUserCannotUnlinkLastMethodWithoutEmailAndStaffIsBlocked() throws Exception {
    // Facebook without email -> creates account, blocks unlinking sole provider until email added
    nextFacebookId = "fb-no-email-3001";
    nextFacebookEmail = null;
    nextFacebookName = "Khách Không Email";
    String code = runOAuthBrowserFlow("facebook", "yofasomobile://auth-callback");
    var res = postJson("/auth/oauth/exchange", null, Map.of("code", code), 200);
    assertEquals("AUTHENTICATED", res.get("status"));
    String fbToken = res.get("accessToken").toString();

    var unlinkBlocked =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/me/linked-accounts/facebook"))
                .header("Authorization", "Bearer " + fbToken)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(409, unlinkBlocked.statusCode());

    // Staff account email must be blocked from Social Login
    String staffId = Db.id("ND");
    db.exec(
        "INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai,Email) VALUES(?,?,?,?,?,?)",
        staffId,
        "staffoauth",
        passwords.encode("StaffSecret123"),
        "Staff OAuth",
        "0988777666",
        "staff.oauth@gmail.com");
    db.exec("INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro) VALUES(?, 'VT001')", staffId);

    nextGoogleSub = "gg-staff-attempt-999";
    nextGoogleEmail = "staff.oauth@gmail.com";
    var startRes =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/auth/oauth/google/start?redirectUri=yofasomobile://auth-callback"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String state = queryParam(startRes.headers().firstValue("Location").orElseThrow(), "state");
    var cbRes =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/auth/oauth/google/callback?code=staffcode&state="
                            + state))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(302, cbRes.statusCode());
    String cbLocation = cbRes.headers().firstValue("Location").orElseThrow();
    assertEquals("OAUTH_STAFF_ACCOUNT_FORBIDDEN", queryParam(cbLocation, "oauthError"));
  }

  private String runOAuthBrowserFlow(String provider, String redirectUri) throws Exception {
    var startRes =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/auth/oauth/"
                            + provider
                            + "/start?redirectUri="
                            + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(302, startRes.statusCode());
    String authLocation = startRes.headers().firstValue("Location").orElseThrow();
    String state = queryParam(authLocation, "state");
    assertNotNull(state);

    var cbRes =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/auth/oauth/"
                            + provider
                            + "/callback?code=stubcode123&state="
                            + state))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(302, cbRes.statusCode());
    String appLocation = cbRes.headers().firstValue("Location").orElseThrow();
    assertTrue(appLocation.startsWith(redirectUri), "Unexpected redirect: " + appLocation);
    String oauthCode = queryParam(appLocation, "oauthCode");
    assertNotNull(oauthCode, "Expected oauthCode in " + appLocation);
    return oauthCode;
  }

  private static String queryParam(String url, String key) {
    int idx = url.indexOf('?');
    if (idx < 0) return null;
    for (String pair : url.substring(idx + 1).split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv.length == 2 && kv[0].equals(key)) {
        return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getJson(String path, String token, int expected) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).GET();
    if (token != null) b.header("Authorization", "Bearer " + token);
    var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, res.statusCode(), res.body());
    return json.readValue(res.body(), Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> postJson(String path, String token, Object body, int expected)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (token != null) b.header("Authorization", "Bearer " + token);
    var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, res.statusCode(), res.body());
    return json.readValue(res.body(), Map.class);
  }
}
