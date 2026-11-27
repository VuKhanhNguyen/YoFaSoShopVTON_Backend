package com.yofaso.shop.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.common.RuntimeState;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bộ kiểm thử đơn vị chuyên sâu cho toàn bộ hệ thống Thông báo, Push Notification (Expo + Web Push RFC 8291/8292),
 * SSE Realtime, Chống spam tồn kho 24h, Broadcast và Chính sách dọn dữ liệu 90 ngày.
 */
class NotificationSystemTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void webPushCryptoRoundtripAndVapidSignature() throws Exception {
    WebPushCrypto.VapidKeyPair vapid = WebPushCrypto.generateVapidKeyPair();
    assertNotNull(vapid.publicKey());
    assertNotNull(vapid.privateKey());
    assertEquals(65, Base64.getUrlDecoder().decode(vapid.publicKey()).length);
    assertEquals(32, Base64.getUrlDecoder().decode(vapid.privateKey()).length);

    // Sinh khóa thiết bị trình duyệt (UA)
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair uaPair = kpg.generateKeyPair();
    ECPublicKey uaPub = (ECPublicKey) uaPair.getPublic();
    ECPrivateKey uaPriv = (ECPrivateKey) uaPair.getPrivate();
    byte[] authSecret = new byte[16];
    new SecureRandom().nextBytes(authSecret);
    String p256dhB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(WebPushCrypto.encodeUncompressedPublicKey(uaPub));
    String authB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(authSecret);

    String original = "{\"title\":\"Đơn mới #DH00000099\",\"body\":\"Khách vừa thanh toán VietQR 1.250.000đ\"}";
    byte[] encrypted = WebPushCrypto.encryptAes128Gcm(original.getBytes(StandardCharsets.UTF_8), p256dhB64, authB64);
    byte[] decrypted = WebPushCrypto.decryptAes128Gcm(encrypted, uaPriv, uaPub, authSecret);
    assertEquals(original, new String(decrypted, StandardCharsets.UTF_8));

    // Xác minh chữ ký ES256 của header VAPID
    String header = WebPushCrypto.createVapidAuthHeader(
        "https://fcm.googleapis.com/fcm/send/sample-sub",
        "mailto:admin@yofasoshop.vn",
        vapid.publicKey(),
        vapid.privateKey());
    assertTrue(header.startsWith("vapid t="));
    String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
    String[] parts = jwt.split("\\.");
    assertEquals(3, parts.length);

    String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    assertTrue(payloadJson.contains("\"aud\":\"https://fcm.googleapis.com\""));
    assertTrue(payloadJson.contains("\"sub\":\"mailto:admin@yofasoshop.vn\""));

    Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
    verify.initVerify(WebPushCrypto.decodeUncompressedPublicKey(Base64.getUrlDecoder().decode(vapid.publicKey())));
    verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])));
  }

  @Test
  void pushServiceExpoBatchBearerSanitizeAndDeviceNotRegistered() throws Exception {
    AtomicReference<String> authHeader = new AtomicReference<>();
    AtomicReference<String> receivedBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/push/send",
        exchange -> {
          authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] resp =
              ("{\"data\":[{\"status\":\"ok\",\"id\":\"t1\"},"
                      + "{\"status\":\"error\",\"message\":\"Not registered\",\"details\":{\"error\":\"DeviceNotRegistered\"}}]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, resp.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
          }
        });
    server.start();
    try {
      Db db = mock(Db.class);
      String liveToken = "ExponentPushToken[live-token-111]";
      String deadToken = "ExponentPushToken[dead-token-222]";
      when(db.rows(contains("FROM ThietBiThongBao"), eq("ND00000005")))
          .thenReturn(
              List.of(
                  Map.of("MaThietBi", "DT00000001", "NenTang", "ANDROID", "DiaChiNhanThongBao", liveToken),
                  Map.of("MaThietBi", "DT00000002", "NenTang", "ANDROID", "DiaChiNhanThongBao", deadToken)));

      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push/send";
      PushNotificationService service =
          new PushNotificationService(
              db, json, HttpClient.newHttpClient(), true, endpoint, "secret-expo-token", "", "", "");

      service.dispatchToUserSync(
          "ND00000005",
          "Shipper đang giao đơn #DH00000001",
          "Liên hệ shipper theo số 0912345678 khi cần hỗ trợ.",
          "DON_HANG",
          "/orders/DH00000001",
          "TB00000099",
          3,
          Map.of());

      assertEquals("Bearer secret-expo-token", authHeader.get());
      assertNotNull(receivedBody.get());
      assertTrue(receivedBody.get().contains("\"channelId\":\"orders\""));
      assertTrue(receivedBody.get().contains("\"badge\":3"));
      // SĐT trên banner màn hình khóa phải được ẩn
      assertFalse(receivedBody.get().contains("0912345678"));
      assertTrue(receivedBody.get().contains("[SĐT đã ẩn]"));

      // Token dead phải tự động bị vô hiệu hóa, token live không bị động tới
      verify(db).exec(contains("SET TrangThaiHoatDong=0"), eq(deadToken));
      verify(db, never()).exec(contains("SET TrangThaiHoatDong=0"), eq(liveToken));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void pushServiceWebPushVapidEncryptsAndDeactivatesOn410Gone() throws Exception {
    WebPushCrypto.VapidKeyPair vapid = WebPushCrypto.generateVapidKeyPair();
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
    kpg.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair uaPair = kpg.generateKeyPair();
    ECPublicKey uaPub = (ECPublicKey) uaPair.getPublic();
    ECPrivateKey uaPriv = (ECPrivateKey) uaPair.getPrivate();
    byte[] authSecret = new byte[16];
    new SecureRandom().nextBytes(authSecret);
    String p256dhB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(WebPushCrypto.encodeUncompressedPublicKey(uaPub));
    String authB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(authSecret);

    AtomicReference<String> receivedEncoding = new AtomicReference<>();
    AtomicReference<String> receivedAuth = new AtomicReference<>();
    AtomicReference<byte[]> receivedCipher = new AtomicReference<>();

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/webpush/sub1",
        exchange -> {
          receivedEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
          receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          receivedCipher.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(410, -1);
          exchange.close();
        });
    server.start();
    try {
      String subUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/webpush/sub1";
      Db db = mock(Db.class);
      when(db.rows(contains("FROM ThietBiThongBao"), eq("ND00000004")))
          .thenReturn(
              List.of(
                  Map.of(
                      "MaThietBi", "DT00000003",
                      "NenTang", "TRINH_DUYET",
                      "DiaChiNhanThongBao", subUrl,
                      "KhoaCongKhaiMaHoa", p256dhB64,
                      "KhoaXacThucMaHoa", authB64)));

      PushNotificationService service =
          new PushNotificationService(
              db,
              json,
              HttpClient.newHttpClient(),
              false,
              "",
              "",
              vapid.publicKey(),
              vapid.privateKey(),
              "mailto:admin@yofasoshop.vn");

      service.dispatchToUserSync(
          "ND00000004",
          "Có kiện hàng mới cần đi giao",
          "Đơn #DH00000077 sẵn sàng xuất kho.",
          "DON_HANG",
          "/shipper/deliveries?order=DH00000077",
          "TB00000100",
          1,
          Map.of());

      assertEquals("aes128gcm", receivedEncoding.get());
      assertNotNull(receivedAuth.get());
      assertTrue(receivedAuth.get().startsWith("vapid t="));
      byte[] plain = WebPushCrypto.decryptAes128Gcm(receivedCipher.get(), uaPriv, uaPub, authSecret);
      String plainStr = new String(plain, StandardCharsets.UTF_8);
      assertTrue(plainStr.contains("DH00000077"));
      // 410 Gone phải tự động tắt subscription
      verify(db).exec(contains("SET TrangThaiHoatDong=0"), eq(subUrl));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void notificationServiceDeviceRegistrationPreferencesLowStockDedupeAndBroadcast() {
    Db db = mock(Db.class);
    RuntimeState runtime = mock(RuntimeState.class);
    when(db.runtime()).thenReturn(runtime);
    Map<String, Map<String, Object>> store = new HashMap<>();
    when(runtime.get(eq("notificationprefs"), anyString()))
        .thenAnswer(inv -> store.getOrDefault(inv.getArgument(1), Map.of()));
    doAnswer(
            inv -> {
              store.put(inv.getArgument(1), inv.getArgument(2));
              return null;
            })
        .when(runtime)
        .put(eq("notificationprefs"), anyString(), anyMap());

    NotificationStreamService stream = new NotificationStreamService(json);
    PushNotificationService push = mock(PushNotificationService.class);
    NotificationService service = new NotificationService(db, stream, push, 5, 90);

    // 1. Validate device registration rules
    assertThrows(
        Problem.class,
        () -> service.registerDevice("ND00000005", "ANDROID", "bad-token-format", null, null, "Phone"));
    assertThrows(
        Problem.class,
        () -> service.registerDevice("ND00000004", "TRINH_DUYET", "http://evil.example/sub", "k", "a", "Web"));
    assertThrows(
        Problem.class,
        () -> service.registerDevice("ND00000004", "TRINH_DUYET", "https://fcm.googleapis.com/sub", "", "", "Web"));

    when(db.rows(contains("SELECT MaThietBi FROM ThietBiThongBao"), eq("ExponentPushToken[abc123]")))
        .thenReturn(List.of());
    var reg = service.registerDevice("ND00000005", "ANDROID", "ExponentPushToken[abc123]", null, null, "Pixel 9");
    assertEquals("ANDROID", reg.get("platform"));
    assertTrue(Boolean.TRUE.equals(reg.get("active")));
    verify(db).exec(contains("INSERT INTO ThietBiThongBao"), any(), eq("ND00000005"), eq("ANDROID"), eq("ExponentPushToken[abc123]"), isNull(), isNull(), eq("Pixel 9"));

    // Chuyển quyền sở hữu nếu user khác đăng nhập cùng máy
    when(db.rows(contains("SELECT MaThietBi FROM ThietBiThongBao"), eq("ExponentPushToken[abc123]")))
        .thenReturn(List.of(Map.of("MaThietBi", "DT00000001")));
    var takeover = service.registerDevice("ND00000006", "ANDROID", "ExponentPushToken[abc123]", null, null, "Pixel 9");
    assertEquals("DT00000001", takeover.get("deviceId"));
    verify(db).exec(contains("UPDATE ThietBiThongBao SET MaNguoiDung=?"), eq("ND00000006"), eq("ANDROID"), isNull(), isNull(), eq("Pixel 9"), eq("DT00000001"));

    // 2. Preferences: tắt KHUYEN_MAI vẫn ghi ThongBao nhưng KHÔNG đẩy Push
    service.updatePreferences("ND00000005", Map.of("KHUYEN_MAI", false));
    when(db.count(contains("FROM ThongBao"), eq("ND00000005"))).thenReturn(1L);
    String idPromo = service.notifyUser("ND00000005", "Sale 50%", "Giảm sâu hôm nay", "KHUYEN_MAI", "/promotions");
    assertNotNull(idPromo);
    verify(push, never()).dispatchToUserAsync(eq("ND00000005"), anyString(), anyString(), eq("KHUYEN_MAI"), any(), any(), anyLong(), anyMap());

    // Bật lại DON_HANG thì có đẩy Push
    String idOrder = service.notifyUser("ND00000005", "Đơn hàng mới", "Đã xác nhận", "DON_HANG", "/orders/DH1");
    assertNotNull(idOrder);
    verify(push, times(1)).dispatchToUserAsync(eq("ND00000005"), eq("Đơn hàng mới"), eq("Đã xác nhận"), eq("DON_HANG"), eq("/orders/DH1"), eq(idOrder), eq(1L), anyMap());

    // 3. pushOnly (Live Chat) KHÔNG ghi bảng ThongBao
    clearInvocations(db, push);
    service.pushOnly("ND00000005", "Chủ shop trả lời", "Chào bạn, mẫu này còn size M!", "LIVE_CHAT", "/chat", Map.of("messageId", "TN0001"));
    verify(db, never()).exec(contains("INSERT INTO ThongBao"), any(Object[].class));
    verify(push, times(1)).dispatchToUserAsync(eq("ND00000005"), eq("Chủ shop trả lời"), eq("Chào bạn, mẫu này còn size M!"), eq("LIVE_CHAT"), eq("/chat"), isNull(), anyLong(), anyMap());

    // 4. Low-stock 24h dedupe cooldown + reset after restock
    clearInvocations(db);
    when(db.rows(contains("FROM BienTheSanPham"), eq("BT00000001")))
        .thenReturn(List.of(Map.of("sku", "YFS-Linen-M", "productName", "Áo Sơ Mi Linen", "size", "M", "color", "Trắng")));
    when(db.rows(contains("TenVaiTro=?"), eq("ROLE_STAFF_WAREHOUSE")))
        .thenReturn(List.of(Map.of("id", "ND00000003")));
    when(db.rows(contains("TenVaiTro=?"), eq("ROLE_SHOP_ADMIN")))
        .thenReturn(List.of(Map.of("id", "ND00000001")));

    service.checkLowStockAndAlert("BT00000001", 3); // Lần 1: bắn cho Kho + Admin (2 bản ghi)
    service.checkLowStockAndAlert("BT00000001", 2); // Lần 2 trong 24h: chặn spam!
    verify(db, times(2)).exec(contains("INSERT INTO ThongBao"), any(Object[].class));

    service.checkLowStockAndAlert("BT00000001", 12); // Nhập thêm > 5: xóa cooldown
    service.checkLowStockAndAlert("BT00000001", 4);  // Lại tụt <= 5: bắn cảnh báo mới (+2 bản ghi)
    verify(db, times(4)).exec(contains("INSERT INTO ThongBao"), any(Object[].class));

    // 5. Broadcast theo hạng thẻ + kiểm tra lỗi tier không hợp lệ
    assertThrows(
        Problem.class,
        () -> service.broadcastToCustomers("ND00000001", "Tiêu đề", "Nội dung", "KHUYEN_MAI", "/promotions", "PLATINUM"));
    when(db.rows(contains("AND u.HangThanhVien=?"), eq("VANG")))
        .thenReturn(List.of(Map.of("id", "ND00000005"), Map.of("id", "ND00000006")));
    var bRes = service.broadcastToCustomers("ND00000001", "Quà hạng Vàng", "Giảm 15%", "KHUYEN_MAI", "/promotions", "VANG");
    assertEquals(2, bRes.get("sentCount"));
    assertEquals("VANG", bRes.get("targetTier"));
    verify(db).audit(eq("ND00000001"), eq("NOTIFICATION_BROADCAST"), eq("VANG:KHUYEN_MAI:2"));

    // 6. Retention cleanup 90 ngày
    when(db.exec(contains("DELETE FROM ThongBao"), eq(90))).thenReturn(14);
    assertEquals(14, service.purgeOldReadNotifications());
    verify(db).exec(contains("DELETE FROM ThietBiThongBao"), eq(180));
  }

  @Test
  void sseStreamServiceCapsConnectionsPerUser() {
    NotificationStreamService stream = new NotificationStreamService(json);
    for (int i = 0; i < 12; i++) {
      stream.subscribe("ND00000001", i);
    }
    assertTrue(stream.activeConnectionCount("ND00000001") <= 10);
    assertDoesNotThrow(() -> stream.sendToUser("ND00000001", "notification", Map.of("title", "Test")));
  }
}
