package com.yofaso.shop.notification;

import com.yofaso.shop.common.Db;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dịch vụ gửi thông báo đẩy bất đồng bộ ra hai kênh:
 * 1) Expo Push Service (Android / iOS)
 * 2) Web Push VAPID chuẩn RFC 8291 (Trình duyệt / PWA)
 * Tự động vô hiệu hóa token chết (DeviceNotRegistered / HTTP 404 / 410).
 */
@Service
public class PushNotificationService {
  private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);
  private static final int EXPO_BATCH_SIZE = 100;

  private final Db db;
  private final JsonMapper json;
  private final HttpClient http;
  private final boolean expoEnabled;
  private final String expoApiUrl;
  private final String expoAccessToken;
  private final String vapidPublicKey;
  private final String vapidPrivateKey;
  private final String vapidSubject;

  @Autowired
  public PushNotificationService(
      Db db,
      JsonMapper json,
      @Value("${app.notifications.expo.enabled:true}") boolean expoEnabled,
      @Value("${app.notifications.expo.api-url:https://exp.host/--/api/v2/push/send}") String expoApiUrl,
      @Value("${app.notifications.expo.access-token:}") String expoAccessToken,
      @Value("${app.notifications.web-push.vapid-public-key:}") String vapidPublicKey,
      @Value("${app.notifications.web-push.vapid-private-key:}") String vapidPrivateKey,
      @Value("${app.notifications.web-push.vapid-subject:mailto:nguyenvukhanh392@gmail.com}") String vapidSubject) {
    this(
        db,
        json,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        expoEnabled,
        expoApiUrl,
        expoAccessToken,
        vapidPublicKey,
        vapidPrivateKey,
        vapidSubject);
  }

  PushNotificationService(
      Db db,
      JsonMapper json,
      HttpClient http,
      boolean expoEnabled,
      String expoApiUrl,
      String expoAccessToken,
      String vapidPublicKey,
      String vapidPrivateKey,
      String vapidSubject) {
    this.db = db;
    this.json = json;
    this.http = http;
    this.expoEnabled = expoEnabled;
    this.expoApiUrl = expoApiUrl == null ? "" : expoApiUrl.trim();
    this.expoAccessToken = expoAccessToken == null ? "" : expoAccessToken.trim();
    this.vapidPublicKey = vapidPublicKey == null ? "" : vapidPublicKey.trim();
    this.vapidPrivateKey = vapidPrivateKey == null ? "" : vapidPrivateKey.trim();
    this.vapidSubject = vapidSubject == null ? "" : vapidSubject.trim();
  }

  public boolean isWebPushConfigured() {
    return !vapidPublicKey.isBlank() && !vapidPrivateKey.isBlank();
  }

  public String getWebPushPublicKey() {
    return vapidPublicKey;
  }

  /** Đẩy thông báo bất đồng bộ tới toàn bộ thiết bị đang hoạt động của userId. */
  @Async("notificationExecutor")
  public void dispatchToUserAsync(
      String userId,
      String title,
      String body,
      String type,
      String link,
      String notificationId,
      long unreadCount,
      Map<String, Object> extraData) {
    dispatchToUserSync(userId, title, body, type, link, notificationId, unreadCount, extraData);
  }

  /** Thực thi đồng bộ (hữu ích cho test và gọi từ luồng async). */
  public void dispatchToUserSync(
      String userId,
      String title,
      String body,
      String type,
      String link,
      String notificationId,
      long unreadCount,
      Map<String, Object> extraData) {
    List<Map<String, Object>> devices;
    try {
      devices =
          db.rows(
              "SELECT MaThietBi, NenTang, DiaChiNhanThongBao, KhoaCongKhaiMaHoa, KhoaXacThucMaHoa "
                  + "FROM ThietBiThongBao WHERE MaNguoiDung=? AND TrangThaiHoatDong=1",
              userId);
    } catch (Exception e) {
      log.warn("Không đọc được ThietBiThongBao cho {}: {}", userId, e.getMessage());
      return;
    }
    if (devices.isEmpty()) return;

    String safeTitle = sanitizeBannerText(title, 100);
    String safeBody = sanitizeBannerText(body, 180);
    Map<String, Object> dataPayload = new LinkedHashMap<>();
    if (notificationId != null && !notificationId.isBlank()) {
      dataPayload.put("notificationId", notificationId);
    }
    dataPayload.put("type", type);
    if (link != null && !link.isBlank()) {
      dataPayload.put("link", link);
    }
    dataPayload.put("unreadCount", unreadCount);
    if (extraData != null && !extraData.isEmpty()) {
      dataPayload.putAll(extraData);
    }

    List<String> expoTokens = new ArrayList<>();
    List<Map<String, Object>> webDevices = new ArrayList<>();
    for (var d : devices) {
      String platform = Db.text(d, "NenTang");
      String addr = Db.text(d, "DiaChiNhanThongBao");
      if (addr.isBlank()) continue;
      if ("TRINH_DUYET".equals(platform)) {
        webDevices.add(d);
      } else if (addr.startsWith("ExponentPushToken[") || addr.startsWith("ExpoPushToken[")) {
        expoTokens.add(addr);
      }
    }

    if (expoEnabled && !expoApiUrl.isBlank() && !expoTokens.isEmpty()) {
      sendExpoBatches(expoTokens, safeTitle, safeBody, type, unreadCount, dataPayload);
    }
    if (isWebPushConfigured() && !webDevices.isEmpty()) {
      sendWebPushList(webDevices, safeTitle, safeBody, type, link, notificationId, unreadCount, dataPayload);
    }
  }

  private void sendExpoBatches(
      List<String> tokens,
      String title,
      String body,
      String type,
      long unreadCount,
      Map<String, Object> dataPayload) {
    String channelId = switch (type) {
      case "DON_HANG" -> "orders";
      case "THU_DO_VTON" -> "vton";
      case "KHUYEN_MAI" -> "promotions";
      case "LIVE_CHAT" -> "chat";
      default -> "default";
    };
    for (int i = 0; i < tokens.size(); i += EXPO_BATCH_SIZE) {
      List<String> batch = tokens.subList(i, Math.min(i + EXPO_BATCH_SIZE, tokens.size()));
      List<Map<String, Object>> messages = new ArrayList<>(batch.size());
      for (String token : batch) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("to", token);
        msg.put("title", title);
        msg.put("body", body);
        msg.put("sound", "default");
        msg.put("channelId", channelId);
        msg.put("priority", "high");
        if (unreadCount >= 0) {
          msg.put("badge", (int) Math.min(unreadCount, 999));
        }
        msg.put("data", dataPayload);
        messages.add(msg);
      }
      try {
        String payload = json.writeValueAsString(messages);
        HttpRequest.Builder req =
            HttpRequest.newBuilder(URI.create(expoApiUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (!expoAccessToken.isBlank()) {
          req.header("Authorization", "Bearer " + expoAccessToken);
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
          handleExpoTickets(batch, res.body());
        } else {
          log.warn("Expo Push trả HTTP {}: {}", res.statusCode(), truncate(res.body(), 200));
        }
      } catch (Exception e) {
        log.warn("Lỗi gửi Expo Push: {}", e.getMessage());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void handleExpoTickets(List<String> batchTokens, String responseBody) {
    try {
      Map<String, Object> root = json.readValue(responseBody, Map.class);
      Object dataObj = root.get("data");
      if (!(dataObj instanceof List<?> tickets)) return;
      for (int idx = 0; idx < Math.min(tickets.size(), batchTokens.size()); idx++) {
        if (!(tickets.get(idx) instanceof Map<?, ?> ticket)) continue;
        if ("error".equals(ticket.get("status"))) {
          Object details = ticket.get("details");
          String errCode =
              (details instanceof Map<?, ?> dm && dm.get("error") != null)
                  ? dm.get("error").toString()
                  : "";
          if ("DeviceNotRegistered".equals(errCode)) {
            deactivateToken(batchTokens.get(idx));
          } else {
            log.warn("Expo ticket lỗi [{}]: {}", errCode, ticket.get("message"));
          }
        }
      }
    } catch (Exception e) {
      log.debug("Không parse được Expo Push ticket response: {}", e.getMessage());
    }
  }

  private void sendWebPushList(
      List<Map<String, Object>> devices,
      String title,
      String body,
      String type,
      String link,
      String notificationId,
      long unreadCount,
      Map<String, Object> dataPayload) {
    Map<String, Object> pushJson = new LinkedHashMap<>();
    pushJson.put("title", title);
    pushJson.put("body", body);
    pushJson.put("type", type);
    pushJson.put("link", link == null ? "/" : link);
    pushJson.put("notificationId", notificationId);
    pushJson.put("unreadCount", unreadCount);
    pushJson.put("icon", "/brand/YoFaSo_logo.png");
    pushJson.put("badge", "/brand/YoFaSo_logo.png");
    pushJson.put("data", dataPayload);
    byte[] plainBytes;
    try {
      plainBytes = json.writeValueAsBytes(pushJson);
    } catch (Exception e) {
      return;
    }

    for (var d : devices) {
      String endpoint = Db.text(d, "DiaChiNhanThongBao");
      String p256dh = Db.text(d, "KhoaCongKhaiMaHoa");
      String auth = Db.text(d, "KhoaXacThucMaHoa");
      if (endpoint.isBlank() || p256dh.isBlank() || auth.isBlank()) continue;
      try {
        byte[] encrypted = WebPushCrypto.encryptAes128Gcm(plainBytes, p256dh, auth);
        String vapidHeader =
            WebPushCrypto.createVapidAuthHeader(endpoint, vapidSubject, vapidPublicKey, vapidPrivateKey);
        HttpRequest req =
            HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", vapidHeader)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", "86400")
                .header("Urgency", "high")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encrypted))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404 || res.statusCode() == 410) {
          deactivateToken(endpoint);
        } else if (res.statusCode() >= 400) {
          log.warn("Web Push tới {} trả HTTP {}", truncate(endpoint, 60), res.statusCode());
        }
      } catch (Exception e) {
        log.warn("Lỗi gửi Web Push tới {}: {}", truncate(endpoint, 60), e.getMessage());
      }
    }
  }

  private void deactivateToken(String tokenOrEndpoint) {
    try {
      db.exec(
          "UPDATE ThietBiThongBao SET TrangThaiHoatDong=0, NgayCapNhat=CURRENT_TIMESTAMP "
              + "WHERE DiaChiNhanThongBao=?",
          tokenOrEndpoint);
    } catch (Exception ignored) {}
  }

  /**
   * Làm sạch nội dung hiển thị trên màn hình khóa:
   * - Không phơi bày chuỗi OTP 6 chữ số hay số điện thoại chi tiết ngoài màn hình khóa.
   * - Cắt gọn độ dài hợp lý cho banner OS.
   */
  public static String sanitizeBannerText(String raw, int maxLength) {
    if (raw == null) return "";
    String clean = raw.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
    // Che số điện thoại Việt Nam (0xxxxxxxxx hoặc +84xxxxxxxxx) trên banner màn hình khóa
    clean = clean.replaceAll("(\\+84|0)(3|5|7|8|9)\\d{8}\\b", "[SĐT đã ẩn]");
    return truncate(clean, maxLength);
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
  }
}
