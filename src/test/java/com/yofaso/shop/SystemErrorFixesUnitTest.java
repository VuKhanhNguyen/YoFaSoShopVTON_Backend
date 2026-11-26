package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yofaso.shop.ai.AdminAiController;
import com.yofaso.shop.ai.AdminAiService;
import com.yofaso.shop.ai.MediaService;
import com.yofaso.shop.chat.ChatService;
import com.yofaso.shop.chat.OpenRouterClient;
import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Idempotency;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.order.InventoryService;
import com.yofaso.shop.order.OrderController;
import com.yofaso.shop.order.OrderService;
import com.yofaso.shop.order.StaffController;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests verifying critical system fixes:
 * 1) Combo VTON -5% calculated BEFORE Voucher on (subtotal - comboDiscount), plus shipping fee rules.
 * 2) Chat customer imageKey IDOR rejection (403 FORBIDDEN).
 * 3) AdminAiService deterministic fallbacks & spam/negative review flagging when LLM is offline.
 */
class SystemErrorFixesUnitTest {

  @Test
  void testCombo5PercentCalculatedBeforePercentageVoucherAndShippingSynced() {
    Db db = mock(Db.class);
    InventoryService stock = mock(InventoryService.class);
    Idempotency keys = mock(Idempotency.class);
    OrderService orders = new OrderService(db, stock, keys);

    // 2 variants: Top 400,000 + Bottom 600,000 = 1,000,000 subtotal
    when(db.one(contains("FROM BienTheSanPham"), eq("BT00000001")))
        .thenReturn(new LinkedHashMap<>(Map.of("price", BigDecimal.valueOf(400_000))));
    when(db.one(contains("FROM BienTheSanPham"), eq("BT00000002")))
        .thenReturn(new LinkedHashMap<>(Map.of("price", BigDecimal.valueOf(600_000))));

    // Matching successful VTON session + GoiYPhoiDo pair owned by ND00000001
    when(db.rows(contains("FROM PhienThuDoAo p JOIN GoiYPhoiDo g"), eq("ND00000001")))
        .thenReturn(
            List.of(
                new LinkedHashMap<>(
                    Map.of(
                        "sessionId", "TD00000001",
                        "mainVariant", "BT00000001",
                        "recVariant", "BT00000002"))));

    // 10% voucher with cap 200,000, min order 500,000
    when(db.rows(contains("FROM KhuyenMai WHERE MaPhieuGiamGia=?"), eq("SAVE10")))
        .thenReturn(
            List.of(
                new LinkedHashMap<>(
                    Map.of(
                        "MaKhuyenMai", "KM000001",
                        "MaPhieuGiamGia", "SAVE10",
                        "TenChuongTrinh", "Giảm 10% toàn shop",
                        "LoaiGiamGia", "PHAN_TRAM",
                        "GiaTriGiam", BigDecimal.valueOf(10),
                        "GiaTriDonHangToiThieu", BigDecimal.valueOf(500_000),
                        "GiamToiDa", BigDecimal.valueOf(200_000),
                        "SoLuongDaDung", 2,
                        "SoLuongPhatHanh", 100))));

    var items =
        List.of(
            new OrderController.Item("BT00000001", 1, "TD00000001"),
            new OrderController.Item("BT00000002", 1, "TD00000001"));

    Map<String, Object> quote = orders.quoteOrder("ND00000001", items, "SAVE10", "TD00000001", false, false);

    // Subtotal = 1,000,000
    // Step 1: Combo -5% of 1,000,000 = 50,000
    // Step 2: Voucher 10% applied to (1,000,000 - 50,000) = 950,000 -> 95,000 (NOT 100,000!)
    // Total discount = 145,000; Shipping = 0 (subtotal >= 1,000,000)
    // Grand total = 1,000,000 - 145,000 = 855,000
    assertEquals(0, BigDecimal.valueOf(1_000_000).compareTo((BigDecimal) quote.get("subtotal")));
    assertEquals(true, quote.get("comboEligible"));
    assertEquals(0, BigDecimal.valueOf(50_000).compareTo((BigDecimal) quote.get("comboDiscount")));
    assertEquals(0, BigDecimal.valueOf(95_000).compareTo((BigDecimal) quote.get("voucherDiscount")));
    assertEquals(0, BigDecimal.valueOf(145_000).compareTo((BigDecimal) quote.get("totalDiscount")));
    assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) quote.get("shippingFee")));
    assertEquals(0, BigDecimal.valueOf(855_000).compareTo((BigDecimal) quote.get("total")));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> details = (List<Map<String, Object>>) quote.get("discountDetails");
    assertTrue(details.stream().anyMatch(d -> "COMBO_VTON".equals(d.get("kind"))));
    assertTrue(details.stream().anyMatch(d -> "VOUCHER".equals(d.get("kind"))));
  }

  @Test
  void testSingleItemHasNoComboDiscountAndCharges30kOnlineShipping() {
    Db db = mock(Db.class);
    OrderService orders = new OrderService(db, mock(InventoryService.class), mock(Idempotency.class));

    when(db.one(contains("FROM BienTheSanPham"), eq("BT00000001")))
        .thenReturn(new LinkedHashMap<>(Map.of("price", BigDecimal.valueOf(350_000))));

    var items = List.of(new OrderController.Item("BT00000001", 1));

    Map<String, Object> onlineQuote = orders.quoteOrder("ND00000001", items, null, null, false, false);
    assertEquals(false, onlineQuote.get("comboEligible"));
    assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) onlineQuote.get("comboDiscount")));
    assertEquals(0, BigDecimal.valueOf(30_000).compareTo((BigDecimal) onlineQuote.get("shippingFee")));
    assertEquals(0, BigDecimal.valueOf(380_000).compareTo((BigDecimal) onlineQuote.get("total")));

    Map<String, Object> posQuote = orders.quoteOrder("ND00000001", items, null, null, true, false);
    assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) posQuote.get("shippingFee")));
    assertEquals(0, BigDecimal.valueOf(350_000).compareTo((BigDecimal) posQuote.get("total")));
  }

  @Test
  void testChatCustomerImageKeyIdorBlockedWith403() {
    Db db = mock(Db.class);
    MediaService media = mock(MediaService.class);
    OpenRouterClient llm = mock(OpenRouterClient.class);
    ChatService chat =
        new ChatService(
            db,
            media,
            llm,
            JsonMapper.builder().build(),
            "0795605214",
            "0795605214",
            "shop@yofaso.vn",
            "08:00 - 22:00",
            "YoFaSo",
            "48 Cao Thang");

    Problem ex =
        assertThrows(
            Problem.class,
            () ->
                chat.sendCustomerMessage(
                    "ND00000001",
                    "CHU_SHOP",
                    null,
                    "Xem giúp ảnh này",
                    "chat/ND00000002/victim-image.png",
                    null,
                    null,
                    "127.0.0.1"));
    assertEquals(403, ex.status);
    assertEquals("CHAT_IMAGE_FORBIDDEN", ex.code);
  }

  @Test
  void testAdminAiServiceFallbacksAndSpamFlagging() {
    Db db = mock(Db.class);
    OpenRouterClient llm = mock(OpenRouterClient.class);
    StaffController staff = mock(StaffController.class);
    when(llm.isConfigured()).thenReturn(false);

    AdminAiService ai = new AdminAiService(db, llm, staff, JsonMapper.builder().build());

    // 1. Product description fallback
    var descRes =
        ai.generateProductDescription(
            new AdminAiController.DescriptionRequest(
                "Áo sơ mi lụa trắng",
                "100% Lụa Satin",
                "upper_body",
                "SUONG_VUA",
                "AO_TIEU_CHUAN",
                "VAI_TRON",
                "Trắng, Kem",
                "NU",
                "Công sở"));
    assertEquals(false, descRes.get("usedRemote"));
    assertTrue(String.valueOf(descRes.get("description")).contains("Áo sơ mi lụa trắng"));

    // 2. Review assist spam/negative local detection
    when(db.one(contains("FROM DanhGiaSanPham"), eq("DG00000001")))
        .thenReturn(
            new LinkedHashMap<>(
                Map.of(
                    "reviewId", "DG00000001",
                    "stars", 1,
                    "comment", "Hàng tệ quá, liên hệ zalo 0912345678 hoặc http://spam.vn mua rẻ hơn",
                    "status", "CHO_DUYET",
                    "productName", "Áo sơ mi lụa",
                    "customerName", "Nguyễn Văn A")));
    var reviewRes = ai.assistReview("DG00000001");
    assertEquals("TIEU_CUC", reviewRes.get("sentiment"));
    assertEquals(true, reviewRes.get("needsAttention"));
    @SuppressWarnings("unchecked")
    List<String> flags = (List<String>) reviewRes.get("flags");
    assertFalse(flags.isEmpty());
    @SuppressWarnings("unchecked")
    List<String> replies = (List<String>) reviewRes.get("suggestedReplies");
    assertFalse(replies.isEmpty());

    // 3. Chat suggest uses real TinNhanTrucTuyen table and returns fallback quick replies
    when(db.one(contains("FROM NguoiDung"), eq("ND00000001")))
        .thenReturn(
            new LinkedHashMap<>(
                Map.of("userId", "ND00000001", "fullName", "Trần Thu Hà", "membership", "VANG")));
    when(db.rows(contains("FROM TinNhanTrucTuyen"), eq("ND00000001")))
        .thenReturn(
            new ArrayList<>(
                List.of(
                    new LinkedHashMap<>(
                        Map.of("sender", "KHACH_HANG", "content", "Đơn mình giao tới đâu rồi shop?")))));
    when(db.rows(contains("FROM DonHang"), eq("ND00000001")))
        .thenReturn(
            List.of(
                new LinkedHashMap<>(
                    Map.of("orderId", "DH00000009", "status", "DANG_GIAO_HANG", "paymentStatus", "DA_THANH_TOAN"))));
    when(db.rows(contains("FROM PhienThuDoAo"), eq("ND00000001"))).thenReturn(List.of());
    var chatRes = ai.suggestChatReplies("ND00000001");
    assertEquals(false, chatRes.get("usedRemote"));
    @SuppressWarnings("unchecked")
    List<String> chatReplies = (List<String>) chatRes.get("suggestedReplies");
    assertTrue(chatReplies.size() >= 2);
    assertTrue(chatReplies.stream().anyMatch(r -> r.contains("DH00000009")));
  }
}
