package com.yofaso.shop.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Các công cụ AI vận hành dành riêng cho Chủ shop (SHOP_ADMIN); luôn chờ người duyệt trước khi lưu/gửi. */
@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('SHOP_ADMIN')")
public class AdminAiController {

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record DescriptionRequest(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 150) String material,
      @Size(max = 40) String vtonCategory,
      @Size(max = 40) String fit,
      @Size(max = 40) String length,
      @Size(max = 40) String pattern,
      @Size(max = 200) String colors,
      @Size(max = 20) String gender,
      @Size(max = 300) String styleHint) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record ReviewAssistRequest(@NotBlank @Size(max = 30) String reviewId) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatSuggestRequest(@NotBlank @Size(max = 30) String customerId) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record ExecutiveSummaryRequest(Boolean forceRefresh) {}

  private final AdminAiService adminAi;

  public AdminAiController(AdminAiService adminAi) {
    this.adminAi = adminAi;
  }

  @PostMapping("/generate-description")
  public Map<String, Object> generateDescription(@Valid @RequestBody DescriptionRequest body) {
    return adminAi.generateProductDescription(body);
  }

  @PostMapping("/review-assist")
  public Map<String, Object> reviewAssist(@Valid @RequestBody ReviewAssistRequest body) {
    return adminAi.assistReview(body.reviewId());
  }

  @PostMapping("/chat-suggest")
  public Map<String, Object> chatSuggest(@Valid @RequestBody ChatSuggestRequest body) {
    return adminAi.suggestChatReplies(body.customerId());
  }

  @PostMapping("/executive-summary")
  public Map<String, Object> executiveSummary(
      @RequestBody(required = false) ExecutiveSummaryRequest body) {
    boolean force = body != null && Boolean.TRUE.equals(body.forceRefresh());
    return adminAi.executiveSummary(force);
  }
}
