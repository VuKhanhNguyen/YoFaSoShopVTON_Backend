package com.yofaso.shop.chat;

import com.yofaso.shop.ai.MediaService;
import com.yofaso.shop.common.Problem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** API Trợ lý AI Thời trang (công khai cho Guest & Khách hàng) và Live Chat CSKH Chủ shop. */
@RestController
@RequestMapping("/api/v1")
public class ChatController {
  public record SendMessageRequest(
      @Pattern(regexp = "TRO_LY_AI|CHU_SHOP") String channel,
      @Size(max = 36) String sessionId,
      @Size(max = 2000) String content,
      @Size(max = 255) String imageKey,
      @Size(max = 30) String vtonSessionId,
      @Size(max = 20) String orderId) {}

  public record AdminReplyRequest(
      @Size(max = 2000) String content,
      @Size(max = 255) String imageKey) {}

  private final ChatService chat;
  private final MediaService media;

  public ChatController(ChatService chat, MediaService media) {
    this.chat = chat;
    this.media = media;
  }

  /** Thông tin hỗ trợ, hotline/Zalo, email, showroom công khai. */
  @GetMapping("/chat/config")
  public Map<String, Object> config() {
    return chat.supportConfig();
  }

  /** Tải lịch sử tin nhắn trên kênh TRO_LY_AI (Guest/Khách hàng) hoặc CHU_SHOP (Khách hàng đã đăng nhập). */
  @GetMapping("/chat/messages")
  public Map<String, Object> listMessages(
      Authentication auth,
      @RequestParam(defaultValue = "TRO_LY_AI") String channel,
      @RequestParam(required = false) String sessionId,
      @RequestParam(defaultValue = "50") int limit) {
    String userId = auth != null ? auth.getName() : null;
    return chat.listCustomerMessages(userId, channel, sessionId, limit);
  }

  /** Gửi tin nhắn tới Trợ lý AI hoặc Chủ shop. */
  @PostMapping("/chat/messages")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> sendMessage(
      Authentication auth,
      HttpServletRequest request,
      @Valid @RequestBody SendMessageRequest body) {
    String userId = auth != null ? auth.getName() : null;
    return chat.sendCustomerMessage(
        userId,
        body.channel(),
        body.sessionId(),
        body.content(),
        body.imageKey(),
        body.vtonSessionId(),
        body.orderId(),
        request.getRemoteAddr());
  }

  /** Upload ảnh đính kèm cho tin nhắn chat (lưu R2 private prefix chat/{owner}/ và cache local). */
  @PostMapping(value = "/chat/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> uploadChatImage(
      Authentication auth,
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String sessionId)
      throws IOException {
    Problem.require(file != null && !file.isEmpty(), 400, "FILE_EMPTY", "Vui lòng chọn ảnh tải lên.");
    String ownerFolder;
    if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
      ownerFolder = auth.getName();
    } else if (sessionId != null && sessionId.matches("[A-Za-z0-9_-]{4,36}")) {
      ownerFolder = sessionId;
    } else {
      ownerFolder = "guest";
    }
    String key = media.saveChatImage(ownerFolder, file.getBytes());
    return Map.of("imageKey", key);
  }

  /** Xem ảnh đính kèm theo mã tin nhắn, kiểm tra quyền sở hữu hoặc ROLE_SHOP_ADMIN. */
  @GetMapping("/chat/images/{messageId}")
  public ResponseEntity<byte[]> messageImage(
      Authentication auth,
      @PathVariable String messageId,
      @RequestParam(required = false) String sessionId) {
    String userId = auth != null ? auth.getName() : null;
    boolean isAdmin = auth != null
        && auth.getAuthorities().stream().anyMatch(g -> "ROLE_SHOP_ADMIN".equals(g.getAuthority()));
    byte[] bytes = chat.readMessageImage(messageId, userId, isAdmin, sessionId);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .contentLength(bytes.length)
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes);
  }

  /** Khách hàng đánh dấu đã đọc tin nhắn từ Chủ shop. */
  @PostMapping("/chat/read")
  public Map<String, Object> markRead(Authentication auth) {
    return chat.markCustomerRead(auth.getName());
  }

  /** Admin Chủ shop xem danh sách khách hàng (ROLE_CUSTOMER) theo thời gian nhắn gần nhất. */
  @GetMapping("/admin/chat/customers")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> adminCustomers(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "false") boolean unreadOnly) {
    return chat.listAdminCustomers(search, unreadOnly);
  }

  /** Admin Chủ shop xem lịch sử Live Chat CSKH với 1 khách hàng. */
  @GetMapping("/admin/chat/customers/{customerId}/messages")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> adminCustomerMessages(
      @PathVariable String customerId,
      @RequestParam(defaultValue = "100") int limit) {
    return chat.listAdminCustomerMessages(customerId, limit);
  }

  /** Admin Chủ shop gửi tin nhắn phản hồi trực tiếp cho khách hàng. */
  @PostMapping("/admin/chat/customers/{customerId}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> adminReply(
      Authentication auth,
      @PathVariable String customerId,
      @Valid @RequestBody AdminReplyRequest body) {
    return chat.sendAdminReply(auth.getName(), customerId, body.content(), body.imageKey());
  }

  /** Admin đánh dấu đã đọc toàn bộ tin của 1 khách hàng. */
  @PostMapping("/admin/chat/customers/{customerId}/read")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> adminMarkRead(@PathVariable String customerId) {
    return chat.markAdminCustomerRead(customerId);
  }

  /** Tổng số tin nhắn CSKH chưa đọc cho badge sidebar Admin. */
  @GetMapping("/admin/chat/unread-count")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> adminUnreadCount() {
    return chat.adminUnreadCount();
  }
}
