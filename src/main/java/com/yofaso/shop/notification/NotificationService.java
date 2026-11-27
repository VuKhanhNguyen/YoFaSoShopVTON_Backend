package com.yofaso.shop.notification;

import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Problem;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Trung tâm điều phối thông báo đa kênh:
 * - Lưu lịch sử vào bảng ThongBao (MySQL)
 * - Đẩy realtime tức thì qua SSE (NotificationStreamService)
 * - Gửi Push bất đồng bộ sau DB commit qua Expo Push & Web Push VAPID (PushNotificationService)
 * - Quản lý thiết bị (ThietBiThongBao), tùy chọn nhóm thông báo, chống spam tồn kho & dọn dữ liệu 90 ngày.
 */
@Service
public class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private static final long LOW_STOCK_COOLDOWN_MS = 24L * 3600 * 1000;
  private static final Set<String> VALID_DB_TYPES =
      Set.of("DON_HANG", "THU_DO_VTON", "CANH_BAO_KHO", "KHUYEN_MAI", "HE_THONG");
  private static final List<String> PREF_KEYS =
      List.of("DON_HANG", "THU_DO_VTON", "CANH_BAO_KHO", "KHUYEN_MAI", "HE_THONG", "LIVE_CHAT");
  private static final Set<String> VALID_TIERS = Set.of("ALL", "DONG", "BAC", "VANG", "KIM_CUONG");

  private final Db db;
  private final NotificationStreamService stream;
  private final PushNotificationService push;
  private final int lowStockThreshold;
  private final int retentionDays;
  private final ConcurrentHashMap<String, Long> lowStockCooldown = new ConcurrentHashMap<>();

  public NotificationService(
      Db db,
      NotificationStreamService stream,
      PushNotificationService push,
      @Value("${app.notifications.low-stock-threshold:5}") int lowStockThreshold,
      @Value("${app.notifications.retention-days:90}") int retentionDays) {
    this.db = db;
    this.stream = stream;
    this.push = push;
    this.lowStockThreshold = Math.max(0, lowStockThreshold);
    this.retentionDays = Math.max(7, retentionDays);
  }

  /** Tạo bản ghi ThongBao, sau đó đẩy SSE & Push bất đồng bộ sau khi DB transaction commit. */
  public String notifyUser(String userId, String title, String content, String type, String link) {
    if (userId == null || userId.isBlank()) return null;
    String safeType = normalizeDbType(type);
    String safeTitle = clip(title == null || title.isBlank() ? "Thông báo từ YoFaSo" : title.trim(), 150);
    String safeContent = content == null ? "" : content.trim();
    String safeLink = (link == null || link.isBlank()) ? null : clip(link.trim(), 255);
    String id = Db.id("TB");
    String createdAt = LocalDateTime.now().toString();
    try {
      db.exec(
          "INSERT INTO ThongBao(MaThongBao,MaNguoiDung,TieuDe,NoiDung,LoaiThongBao,LienKetDieuHuong)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          userId,
          safeTitle,
          safeContent,
          safeType,
          safeLink);
    } catch (Exception e) {
      log.warn("Không ghi được ThongBao cho {}: {}", userId, e.getMessage());
      return null;
    }

    runAfterCommit(
        () -> {
          long unread = unreadCount(userId);
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("notificationId", id);
          payload.put("title", safeTitle);
          payload.put("message", safeContent);
          payload.put("type", safeType);
          payload.put("link", safeLink);
          payload.put("readStatus", 0);
          payload.put("createdAt", createdAt);
          payload.put("unreadCount", unread);
          stream.sendToUser(userId, "notification", payload);

          if (isPushAllowed(userId, safeType)) {
            push.dispatchToUserAsync(
                userId, safeTitle, safeContent, safeType, safeLink, id, unread, Map.of());
          }
        });
    return id;
  }

  /** Gửi thông báo persistent cho tất cả người dùng đang hoạt động thuộc một vai trò. */
  public int notifyRole(String roleName, String title, String content, String type, String link) {
    int count = 0;
    try {
      var users = activeUsersByRole(roleName);
      for (String userId : users) {
        if (notifyUser(userId, title, content, type, link) != null) count++;
      }
    } catch (Exception e) {
      log.warn("Không gửi được thông báo tới vai trò {}: {}", roleName, e.getMessage());
    }
    return count;
  }

  public int notifyAdmins(String title, String content, String type, String link) {
    return notifyRole("ROLE_SHOP_ADMIN", title, content, type, link);
  }

  /**
   * Đẩy realtime + Push KHÔNG ghi vào bảng ThongBao (dùng cho Live Chat CSKH đã lưu ở TinNhanTrucTuyen).
   */
  public void pushOnly(
      String userId,
      String title,
      String content,
      String category,
      String link,
      Map<String, Object> extraData) {
    if (userId == null || userId.isBlank()) return;
    String safeCategory = (category == null || category.isBlank()) ? "LIVE_CHAT" : category.trim().toUpperCase(Locale.ROOT);
    String safeTitle = clip(title == null ? "Tin nhắn mới từ YoFaSo" : title.trim(), 120);
    String safeContent = content == null ? "" : content.trim();
    runAfterCommit(
        () -> {
          Map<String, Object> event = new LinkedHashMap<>();
          event.put("title", safeTitle);
          event.put("message", safeContent);
          event.put("type", safeCategory);
          event.put("link", link);
          if (extraData != null) event.putAll(extraData);
          stream.sendToUser(userId, "chat", event);
          if (isPushAllowed(userId, safeCategory)) {
            long unread = unreadCount(userId);
            push.dispatchToUserAsync(
                userId, safeTitle, safeContent, safeCategory, link, null, unread, extraData);
          }
        });
  }

  /** Đẩy sự kiện Live Chat tới toàn bộ Admin Chủ shop mà không ghi trùng bảng ThongBao. */
  public void pushChatToAdmins(String title, String content, String link, Map<String, Object> extraData) {
    try {
      for (String adminId : activeUsersByRole("ROLE_SHOP_ADMIN")) {
        pushOnly(adminId, title, content, "LIVE_CHAT", link, extraData);
      }
    } catch (Exception e) {
      log.warn("Không gửi được push chat cho admin: {}", e.getMessage());
    }
  }

  /** Kiểm tra tồn kho thấp sau biến động và bắn cảnh báo chống spam (tối đa 1 lần / 24h / SKU). */
  public void checkLowStockAndAlert(String variantId, int afterStock) {
    if (variantId == null || variantId.isBlank()) return;
    if (afterStock > lowStockThreshold) {
      lowStockCooldown.remove(variantId);
      return;
    }
    long now = System.currentTimeMillis();
    Long last = lowStockCooldown.get(variantId);
    if (last != null && (now - last) < LOW_STOCK_COOLDOWN_MS) {
      return;
    }
    lowStockCooldown.put(variantId, now);
    try {
      var rows =
          db.rows(
              "SELECT b.MaQuanLyHangHoa AS sku, b.KichCo AS size, b.TenMau AS color, "
                  + "p.TenSanPham AS productName "
                  + "FROM BienTheSanPham b JOIN SanPham p ON p.MaSanPham=b.MaSanPham "
                  + "WHERE b.MaBienThe=?",
              variantId);
      if (rows.isEmpty()) return;
      var r = rows.getFirst();
      String sku = Db.text(r, "sku");
      String productName = Db.text(r, "productName");
      String size = Db.text(r, "size");
      String color = Db.text(r, "color");
      String title =
          afterStock == 0
              ? "[HẾT HÀNG] " + sku
              : "[CẢNH BÁO TỒN KHO] " + sku + " chỉ còn " + afterStock + " sp";
      String body =
          "Biến thể "
              + sku
              + " ("
              + productName
              + " - "
              + color
              + " / Size "
              + size
              + ") hiện còn "
              + afterStock
              + " sản phẩm khả dụng (ngưỡng an toàn: "
              + lowStockThreshold
              + "). Vui lòng lập kế hoạch nhập bổ sung.";
      notifyRole("ROLE_STAFF_WAREHOUSE", title, body, "CANH_BAO_KHO", "/warehouse/fulfillment?tab=audit");
      notifyAdmins(title, body, "CANH_BAO_KHO", "/admin/dashboard?tab=governance&view=stock");
    } catch (Exception e) {
      log.warn("Lỗi kiểm tra cảnh báo tồn kho cho {}: {}", variantId, e.getMessage());
    }
  }

  /** Danh sách thông báo của người dùng đăng nhập (hỗ trợ lọc chưa đọc & phân trang). */
  public List<Map<String, Object>> list(String userId, boolean unread, int page, int size) {
    int safeSize = Math.max(1, Math.min(size, 100));
    return db.rows(
        "SELECT MaThongBao AS notificationId,TieuDe AS title,NoiDung AS message,LoaiThongBao AS"
            + " type,LienKetDieuHuong AS link,DaDoc AS readStatus,NgayTao AS createdAt FROM"
            + " ThongBao WHERE MaNguoiDung=? AND (?=FALSE OR DaDoc=0) ORDER BY NgayTao DESC, MaThongBao DESC LIMIT ?"
            + " OFFSET ?",
        userId,
        unread,
        safeSize,
        Db.offset(Math.max(0, page), safeSize));
  }

  /** Đếm nhanh số thông báo chưa đọc dựa trên IDX_ThongBao_NguoiDung_DaDoc. */
  public long unreadCount(String userId) {
    try {
      return db.count("SELECT COUNT(*) FROM ThongBao WHERE MaNguoiDung=? AND DaDoc=0", userId);
    } catch (Exception e) {
      return 0L;
    }
  }

  /** Đánh dấu 1 thông báo đã đọc và đồng bộ SSE sang các thiết bị/tab khác. */
  public Map<String, Object> markRead(String userId, String notificationId) {
    db.one("SELECT MaThongBao FROM ThongBao WHERE MaThongBao=? AND MaNguoiDung=?", notificationId, userId);
    db.exec("UPDATE ThongBao SET DaDoc=1 WHERE MaThongBao=?", notificationId);
    long unread = unreadCount(userId);
    stream.sendToUser(userId, "read", Map.of("notificationId", notificationId, "unreadCount", unread));
    return Map.of("read", true, "unreadCount", unread);
  }

  /** Đánh dấu toàn bộ thông báo của người dùng là đã đọc và đồng bộ SSE. */
  public Map<String, Object> markAllRead(String userId) {
    int updated = db.exec("UPDATE ThongBao SET DaDoc=1 WHERE MaNguoiDung=? AND DaDoc=0", userId);
    stream.sendToUser(userId, "read-all", Map.of("updated", updated, "unreadCount", 0));
    return Map.of("updated", updated, "unreadCount", 0);
  }

  /** Đăng ký / chuyển quyền sở hữu thiết bị nhận Push trong bảng ThietBiThongBao. */
  public Map<String, Object> registerDevice(
      String userId,
      String rawPlatform,
      String rawToken,
      String rawP256dh,
      String rawAuth,
      String rawDeviceName) {
    String token = rawToken == null ? "" : rawToken.trim();
    Problem.require(!token.isBlank() && token.length() <= 700,
        422, "PUSH_TOKEN_INVALID", "Địa chỉ nhận thông báo đẩy không hợp lệ.");

    String platform = normalizePlatform(rawPlatform);
    String p256dh = (rawP256dh == null || rawP256dh.isBlank()) ? null : rawP256dh.trim();
    String auth = (rawAuth == null || rawAuth.isBlank()) ? null : rawAuth.trim();
    String deviceName = (rawDeviceName == null || rawDeviceName.isBlank()) ? null : clip(rawDeviceName.trim(), 120);

    if ("TRINH_DUYET".equals(platform)) {
      Problem.require(
          token.startsWith("https://") || token.startsWith("http://localhost") || token.startsWith("http://127.0.0.1"),
          422, "WEB_PUSH_ENDPOINT_INVALID", "Endpoint Web Push phải là URL HTTPS hợp lệ.");
      Problem.require(
          p256dh != null && auth != null && p256dh.length() <= 255 && auth.length() <= 100,
          422, "WEB_PUSH_KEYS_REQUIRED", "Web Push bắt buộc có khóa mã hóa p256dh và auth.");
    } else {
      Problem.require(
          token.startsWith("ExponentPushToken[") || token.startsWith("ExpoPushToken["),
          422, "EXPO_TOKEN_INVALID", "Token thiết bị di động phải có định dạng ExponentPushToken[...] hợp lệ.");
      p256dh = null;
      auth = null;
    }

    var existing =
        db.rows("SELECT MaThietBi FROM ThietBiThongBao WHERE DiaChiNhanThongBao=?", token);
    String deviceId;
    if (!existing.isEmpty()) {
      deviceId = Db.text(existing.getFirst(), "MaThietBi");
      db.exec(
          "UPDATE ThietBiThongBao SET MaNguoiDung=?, NenTang=?, KhoaCongKhaiMaHoa=?, "
              + "KhoaXacThucMaHoa=?, TenThietBi=?, TrangThaiHoatDong=1, NgayCapNhat=CURRENT_TIMESTAMP "
              + "WHERE MaThietBi=?",
          userId,
          platform,
          p256dh,
          auth,
          deviceName,
          deviceId);
    } else {
      deviceId = Db.id("DT");
      db.exec(
          "INSERT INTO ThietBiThongBao("
              + "MaThietBi, MaNguoiDung, NenTang, DiaChiNhanThongBao, KhoaCongKhaiMaHoa, "
              + "KhoaXacThucMaHoa, TenThietBi, TrangThaiHoatDong) "
              + "VALUES(?, ?, ?, ?, ?, ?, ?, 1)",
          deviceId,
          userId,
          platform,
          token,
          p256dh,
          auth,
          deviceName);
    }
    return Map.of("deviceId", deviceId, "platform", platform, "active", true);
  }

  /** Vô hiệu hóa token của thiết bị hiện tại khi người dùng đăng xuất hoặc tắt thông báo đẩy. */
  public Map<String, Object> unregisterDevice(String userId, String rawToken) {
    String token = rawToken == null ? "" : rawToken.trim();
    Problem.require(!token.isBlank() && token.length() <= 700,
        422, "PUSH_TOKEN_INVALID", "Cần cung cấp địa chỉ thiết bị để hủy đăng ký.");
    int updated =
        db.exec(
            "UPDATE ThietBiThongBao SET TrangThaiHoatDong=0, NgayCapNhat=CURRENT_TIMESTAMP "
                + "WHERE MaNguoiDung=? AND DiaChiNhanThongBao=?",
            userId,
            token);
    return Map.of("unregistered", true, "updated", updated);
  }

  /** Đọc tùy chọn bật/tắt thông báo theo nhóm của người dùng (mặc định bật toàn bộ). */
  public Map<String, Boolean> getPreferences(String userId) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (String k : PREF_KEYS) result.put(k, true);
    try {
      var saved = db.runtime().get("notificationprefs", userId);
      for (String k : PREF_KEYS) {
        if (saved.containsKey(k)) {
          result.put(k, Boolean.TRUE.equals(saved.get(k)));
        }
      }
    } catch (Exception ignored) {}
    return result;
  }

  /** Cập nhật tùy chọn bật/tắt từng nhóm thông báo đẩy của người dùng. */
  public Map<String, Boolean> updatePreferences(String userId, Map<String, Boolean> incoming) {
    Map<String, Boolean> merged = getPreferences(userId);
    if (incoming != null) {
      for (var entry : incoming.entrySet()) {
        String key = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase(Locale.ROOT);
        if (merged.containsKey(key) && entry.getValue() != null) {
          merged.put(key, entry.getValue());
        }
      }
    }
    Map<String, Object> toStore = new LinkedHashMap<>();
    toStore.put("userId", userId);
    toStore.put("updatedAt", Instant.now().toString());
    toStore.putAll(merged);
    db.runtime().put("notificationprefs", userId, toStore);
    return merged;
  }

  /** Admin gửi thông báo hàng loạt (Broadcast) tới toàn bộ khách hàng hoặc theo hạng thẻ. */
  public Map<String, Object> broadcastToCustomers(
      String adminId,
      String title,
      String content,
      String type,
      String link,
      String rawTier) {
    String tier = (rawTier == null || rawTier.isBlank()) ? "ALL" : rawTier.trim().toUpperCase(Locale.ROOT);
    Problem.require(VALID_TIERS.contains(tier), 422, "TIER_INVALID", "Hạng thành viên mục tiêu không hợp lệ.");
    String safeType = normalizeDbType(type == null || type.isBlank() ? "KHUYEN_MAI" : type);
    String safeLink = (link == null || link.isBlank()) ? "/promotions" : link.trim();

    List<Map<String, Object>> targets =
        "ALL".equals(tier)
            ? db.rows(
                "SELECT DISTINCT u.MaNguoiDung AS id FROM NguoiDung u "
                    + "JOIN NguoiDung_VaiTro nv ON nv.MaNguoiDung=u.MaNguoiDung "
                    + "JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro "
                    + "WHERE v.TenVaiTro='ROLE_CUSTOMER' AND u.TrangThaiHoatDong='HOAT_DONG'")
            : db.rows(
                "SELECT DISTINCT u.MaNguoiDung AS id FROM NguoiDung u "
                    + "JOIN NguoiDung_VaiTro nv ON nv.MaNguoiDung=u.MaNguoiDung "
                    + "JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro "
                    + "WHERE v.TenVaiTro='ROLE_CUSTOMER' AND u.TrangThaiHoatDong='HOAT_DONG' "
                    + "AND u.HangThanhVien=?",
                tier);

    int sent = 0;
    for (var row : targets) {
      if (notifyUser(Db.text(row, "id"), title, content, safeType, safeLink) != null) {
        sent++;
      }
    }
    db.audit(adminId, "NOTIFICATION_BROADCAST", tier + ":" + safeType + ":" + sent);
    return Map.of("sentCount", sent, "targetTier", tier, "type", safeType);
  }

  /** Chính sách dọn dữ liệu: 03:00 sáng hằng ngày xóa thông báo đã đọc quá retentionDays (mặc định 90 ngày). */
  @Scheduled(cron = "0 0 3 * * *")
  public int purgeOldReadNotifications() {
    try {
      int deleted =
          db.exec(
              "DELETE FROM ThongBao WHERE DaDoc=1 AND NgayTao < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY)",
              retentionDays);
      db.exec(
          "DELETE FROM ThietBiThongBao WHERE TrangThaiHoatDong=0 "
              + "AND NgayCapNhat < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ? DAY)",
          Math.max(retentionDays * 2, 180));
      if (deleted > 0) {
        log.info("Đã dọn {} thông báo đã đọc quá {} ngày.", deleted, retentionDays);
      }
      return deleted;
    } catch (Exception e) {
      log.warn("Lỗi chạy tác vụ dọn thông báo cũ: {}", e.getMessage());
      return 0;
    }
  }

  boolean isPushAllowed(String userId, String category) {
    Map<String, Boolean> prefs = getPreferences(userId);
    return prefs.getOrDefault(category, true);
  }

  private List<String> activeUsersByRole(String roleName) {
    return db
        .rows(
            "SELECT DISTINCT u.MaNguoiDung AS id FROM NguoiDung u "
                + "JOIN NguoiDung_VaiTro nv ON nv.MaNguoiDung=u.MaNguoiDung "
                + "JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro "
                + "WHERE v.TenVaiTro=? AND u.TrangThaiHoatDong='HOAT_DONG'",
            roleName)
        .stream()
        .map(r -> Db.text(r, "id"))
        .filter(s -> !s.isBlank())
        .toList();
  }

  private static void runAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  private static String normalizeDbType(String type) {
    if (type == null) return "HE_THONG";
    String upper = type.trim().toUpperCase(Locale.ROOT);
    return VALID_DB_TYPES.contains(upper) ? upper : "HE_THONG";
  }

  private static String normalizePlatform(String raw) {
    String upper = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    return switch (upper) {
      case "ANDROID" -> "ANDROID";
      case "IOS" -> "IOS";
      case "TRINH_DUYET", "WEB_PUSH", "WEB", "BROWSER", "PWA" -> "TRINH_DUYET";
      default -> throw new Problem(422, "PLATFORM_INVALID", "Nền tảng thiết bị không hợp lệ (ANDROID, IOS, TRINH_DUYET).");
    };
  }

  private static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
