package com.yofaso.shop.notification;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kênh đẩy thời gian thực một chiều (Server-Sent Events) theo từng người dùng.
 * Cho phép Web Portal và Mobile/Web cập nhật badge số lượng & nội dung ngay lập tức không cần reload.
 */
@Service
public class NotificationStreamService {
  private static final long EMITTER_TIMEOUT_MS = 5 * 60 * 1000L;
  private static final int MAX_EMITTERS_PER_USER = 10;

  private final JsonMapper json;
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> streams =
      new ConcurrentHashMap<>();

  public NotificationStreamService(JsonMapper json) {
    this.json = json;
  }

  /** Mở kết nối SSE mới cho người dùng và bắn sự kiện khởi tạo chứa unreadCount hiện tại. */
  public SseEmitter subscribe(String userId, long unreadCount) {
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
    var list = streams.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
    while (list.size() >= MAX_EMITTERS_PER_USER) {
      SseEmitter oldest = list.removeFirst();
      safeComplete(oldest);
    }
    list.add(emitter);

    Runnable remove = () -> removeEmitter(userId, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(e -> remove.run());

    sendSingle(userId, emitter, "connected", Map.of("unreadCount", unreadCount));
    return emitter;
  }

  /** Đẩy sự kiện tới toàn bộ kết nối đang mở của một người dùng. */
  public void sendToUser(String userId, String eventName, Object payload) {
    var list = streams.get(userId);
    if (list == null || list.isEmpty()) return;
    for (SseEmitter emitter : list) {
      sendSingle(userId, emitter, eventName, payload);
    }
  }

  /** Giữ kết nối sống qua proxy / reverse-proxy mỗi 25 giây. */
  @Scheduled(fixedRate = 25000)
  public void heartbeat() {
    streams.forEach(
        (userId, list) -> {
          for (SseEmitter emitter : list) {
            try {
              emitter.send(SseEmitter.event().name("ping").data("ok"));
            } catch (IOException | IllegalStateException e) {
              removeEmitter(userId, emitter);
            }
          }
        });
  }

  public int activeConnectionCount(String userId) {
    var list = streams.get(userId);
    return list == null ? 0 : list.size();
  }

  private void sendSingle(String userId, SseEmitter emitter, String eventName, Object payload) {
    try {
      String serialized = json.writeValueAsString(payload);
      emitter.send(SseEmitter.event().name(eventName).data(serialized));
    } catch (Exception e) {
      removeEmitter(userId, emitter);
    }
  }

  private void removeEmitter(String userId, SseEmitter emitter) {
    var list = streams.get(userId);
    if (list != null) {
      list.remove(emitter);
      if (list.isEmpty()) {
        streams.remove(userId, list);
      }
    }
    safeComplete(emitter);
  }

  private static void safeComplete(SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (Exception ignored) {}
  }
}
