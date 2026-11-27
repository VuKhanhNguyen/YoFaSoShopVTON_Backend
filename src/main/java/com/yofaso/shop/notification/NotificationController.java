package com.yofaso.shop.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** API quản lý thông báo đa kênh, SSE realtime, đăng ký thiết bị Push và Broadcast Admin. */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  public record DeviceRegister(
      @NotBlank @Size(max = 30) String platform,
      @NotBlank @Size(max = 700) String token,
      @Size(max = 255) String p256dh,
      @Size(max = 100) String auth,
      @Size(max = 120) String deviceName) {}

  public record DeviceUnregister(@NotBlank @Size(max = 700) String token) {}

  public record PreferenceUpdate(
      Boolean DON_HANG,
      Boolean THU_DO_VTON,
      Boolean CANH_BAO_KHO,
      Boolean KHUYEN_MAI,
      Boolean HE_THONG,
      Boolean LIVE_CHAT) {}

  public record BroadcastRequest(
      @NotBlank @Size(max = 150) String title,
      @NotBlank @Size(max = 2000) String content,
      @Pattern(regexp = "KHUYEN_MAI|HE_THONG|DON_HANG|THU_DO_VTON") String type,
      @Size(max = 255) String link,
      @Pattern(regexp = "ALL|DONG|BAC|VANG|KIM_CUONG") String targetTier) {}

  private final NotificationService notifications;
  private final NotificationStreamService stream;
  private final PushNotificationService push;

  public NotificationController(
      NotificationService notifications,
      NotificationStreamService stream,
      PushNotificationService push) {
    this.notifications = notifications;
    this.stream = stream;
    this.push = push;
  }

  /** Thông báo của người đăng nhập, hỗ trợ lọc chưa đọc. */
  @GetMapping("/notifications")
  public List<Map<String, Object>> list(
      Authentication a,
      @RequestParam(defaultValue = "false") boolean unread,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return notifications.list(a.getName(), unread, page, size);
  }

  /** Đếm số lượng thông báo chưa đọc của tài khoản hiện tại. */
  @GetMapping("/notifications/unread-count")
  public Map<String, Object> unreadCount(Authentication a) {
    return Map.of("unreadCount", notifications.unreadCount(a.getName()));
  }

  /** Luồng SSE thời gian thực cho badge count và thông báo mới. */
  @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(Authentication a) {
    long count = notifications.unreadCount(a.getName());
    return stream.subscribe(a.getName(), count);
  }

  /** Đánh dấu 1 thông báo đã đọc (kiểm tra ownership). */
  @PostMapping("/notifications/{id}/read")
  public Map<String, Object> read(Authentication a, @PathVariable String id) {
    return notifications.markRead(a.getName(), id);
  }

  /** Đánh dấu toàn bộ thông báo của tài khoản hiện tại là đã đọc. */
  @PostMapping("/notifications/read-all")
  public Map<String, Object> readAll(Authentication a) {
    return notifications.markAllRead(a.getName());
  }

  /** Lấy khóa công khai VAPID để trình duyệt đăng ký Web Push PWA. */
  @GetMapping("/notifications/web-push/public-key")
  public Map<String, Object> webPushPublicKey() {
    boolean enabled = push.isWebPushConfigured();
    return Map.of("enabled", enabled, "publicKey", enabled ? push.getWebPushPublicKey() : "");
  }

  /** Đăng ký hoặc cập nhật thiết bị nhận Push (Mobile Expo Token hoặc Web Push Subscription). */
  @PostMapping("/notifications/devices")
  public Map<String, Object> registerDevice(Authentication a, @Valid @RequestBody DeviceRegister b) {
    return notifications.registerDevice(
        a.getName(), b.platform(), b.token(), b.p256dh(), b.auth(), b.deviceName());
  }

  /** Hủy kích hoạt thiết bị nhận Push khi đăng xuất hoặc người dùng tắt nhận thông báo đẩy. */
  @PostMapping("/notifications/devices/unregister")
  public Map<String, Object> unregisterDevicePost(
      Authentication a, @Valid @RequestBody DeviceUnregister b) {
    return notifications.unregisterDevice(a.getName(), b.token());
  }

  /** Hỗ trợ thêm phương thức DELETE chuẩn REST cho hủy thiết bị. */
  @DeleteMapping("/notifications/devices")
  public Map<String, Object> unregisterDeviceDelete(
      Authentication a, @RequestParam("token") String token) {
    return notifications.unregisterDevice(a.getName(), token);
  }

  /** Đọc cài đặt bật/tắt thông báo đẩy theo từng nhóm của người dùng hiện tại. */
  @GetMapping("/notifications/preferences")
  public Map<String, Boolean> getPreferences(Authentication a) {
    return notifications.getPreferences(a.getName());
  }

  /** Cập nhật cài đặt bật/tắt thông báo đẩy theo từng nhóm. */
  @PutMapping("/notifications/preferences")
  public Map<String, Boolean> updatePreferences(
      Authentication a, @RequestBody PreferenceUpdate b) {
    Map<String, Boolean> map = new LinkedHashMap<>();
    if (b.DON_HANG() != null) map.put("DON_HANG", b.DON_HANG());
    if (b.THU_DO_VTON() != null) map.put("THU_DO_VTON", b.THU_DO_VTON());
    if (b.CANH_BAO_KHO() != null) map.put("CANH_BAO_KHO", b.CANH_BAO_KHO());
    if (b.KHUYEN_MAI() != null) map.put("KHUYEN_MAI", b.KHUYEN_MAI());
    if (b.HE_THONG() != null) map.put("HE_THONG", b.HE_THONG());
    if (b.LIVE_CHAT() != null) map.put("LIVE_CHAT", b.LIVE_CHAT());
    return notifications.updatePreferences(a.getName(), map);
  }

  /** Chủ shop gửi thông báo khuyến mãi / hệ thống hàng loạt tới khách hàng (tất cả hoặc theo hạng thẻ). */
  @PostMapping("/admin/notifications/broadcast")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> broadcast(Authentication a, @Valid @RequestBody BroadcastRequest b) {
    return notifications.broadcastToCustomers(
        a.getName(), b.title(), b.content(), b.type(), b.link(), b.targetTier());
  }
}
