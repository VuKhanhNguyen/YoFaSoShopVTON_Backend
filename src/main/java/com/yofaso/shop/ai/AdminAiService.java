package com.yofaso.shop.ai;

import com.yofaso.shop.chat.OpenRouterClient;
import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.order.StaffController;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Trợ lý AI vận hành nội bộ cho Chủ shop (dùng chung OpenRouterClient + fallback rule-based an toàn). */
@Service
public class AdminAiService {
  private static final long SUMMARY_CACHE_TTL_MS = 10 * 60 * 1000L;
  private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

  private final Db db;
  private final OpenRouterClient llm;
  private final StaffController staff;
  private final JsonMapper json;

  private volatile Map<String, Object> cachedExecutiveSummary;
  private volatile long cachedExecutiveAtMs = 0L;

  public AdminAiService(Db db, OpenRouterClient llm, StaffController staff, JsonMapper json) {
    this.db = db;
    this.llm = llm;
    this.staff = staff;
    this.json = json;
  }

  public Map<String, Object> generateProductDescription(AdminAiController.DescriptionRequest req) {
    String name = safe(req.name(), "Sản phẩm thời trang YoFaSo");
    String material = safe(req.material(), "Chất liệu cao cấp thoáng mát");
    String category = safe(req.vtonCategory(), "upper_body");
    String fit = safe(req.fit(), "SUONG_VUA");
    String length = safe(req.length(), "AO_TIEU_CHUAN");
    String pattern = safe(req.pattern(), "VAI_TRON");
    String colors = safe(req.colors(), "");
    String gender = safe(req.gender(), "UNISEX");
    String styleHint = safe(req.styleHint(), "");

    String systemPrompt =
        "Bạn là biên tập viên thời trang chuyên nghiệp của thương hiệu YoFaSo Shop (Việt Nam). "
            + "Hãy viết mô tả sản phẩm sang trọng, súc tích, chuẩn tiếng Việt, dựa đúng thông số đã cung cấp, KHÔNG bịa chất liệu hay thông số không có. "
            + "Trả về ĐÚNG một đối tượng JSON duy nhất với schema: "
            + "{\"description\": \"đoạn mô tả 2-4 câu tự nhiên (tối đa 450 ký tự)\", \"highlights\": [\"3 đến 4 gạch đầu dòng ngắn gọn\"]}";
    String userPrompt =
        String.format(
            "Tên sản phẩm: %s\nChất liệu: %s\nPhân loại VTON: %s\nKiểu dáng: %s\nChiều dài: %s\nHọa tiết: %s\nBảng màu: %s\nGiới tính: %s\nGhi chú thêm: %s",
            name, material, category, fit, length, pattern, colors, gender, styleHint);

    var completion = callLlm(systemPrompt, userPrompt);
    if (completion.isPresent()) {
      var parsed = parseJsonObject(completion.get().content());
      if (parsed != null && parsed.has("description") && !parsed.get("description").asText("").isBlank()) {
        List<String> highlights = readStringList(parsed.get("highlights"), 4);
        return Map.of(
            "description", parsed.get("description").asText().trim(),
            "highlights", highlights.isEmpty() ? defaultHighlights(material, fit, pattern) : highlights,
            "modelUsed", completion.get().modelUsed(),
            "usedRemote", true);
      }
    }

    String catVn = switch (category) {
      case "lower_body" -> "thiết kế phần dưới tôn dáng";
      case "dress" -> "thiết kế đầm liền thanh lịch";
      default -> "thiết kế áo thời trang hiện đại";
    };
    String colorClause = colors.isBlank() ? "" : " Bảng màu tinh tế (" + colors + ") giúp dễ dàng phối cùng nhiều trang phục sẵn có.";
    String desc =
        String.format(
            "%s là %s từ YoFaSo, sử dụng chất liệu %s mang lại cảm giác thoải mái khi mặc cả ngày. "
                + "Phom dáng chuẩn may đo, tương thích tối ưu với phòng thử đồ ảo AI VTON giúp bạn xem trước độ vừa vặn chuẩn xác.%s",
            name, catVn, material, colorClause);
    return Map.of(
        "description", desc,
        "highlights", defaultHighlights(material, fit, pattern),
        "modelUsed", "local-editorial-fallback",
        "usedRemote", false);
  }

  public Map<String, Object> assistReview(String reviewId) {
    var row =
        db.one(
            "SELECT dg.MaDanhGia AS reviewId, dg.SoSao AS stars, dg.NoiDungBinhLuan AS comment,"
                + " dg.TrangThaiDuyet AS status, s.TenSanPham AS productName, u.HoVaTen AS customerName"
                + " FROM DanhGiaSanPham dg"
                + " JOIN SanPham s ON s.MaSanPham = dg.MaSanPham"
                + " JOIN NguoiDung u ON u.MaNguoiDung = dg.MaNguoiDung"
                + " WHERE dg.MaDanhGia=?",
            reviewId.trim());
    int stars = ((Number) row.get("stars")).intValue();
    String comment = Db.text(row, "comment");
    String product = Db.text(row, "productName");
    String customer = Db.text(row, "customerName");

    List<String> localFlags = detectLocalReviewFlags(comment);

    String systemPrompt =
        "Bạn là trợ lý CSKH của YoFaSo Shop. Hãy đánh giá sắc thái bình luận, phát hiện dấu hiệu spam/ngôn từ tiêu cực/khiếu nại chất lượng, "
            + "và đề xuất 3 câu phản hồi ngắn gọn, lịch sự bằng tiếng Việt để chủ shop chọn duyệt (không tự động gửi). "
            + "Trả về ĐÚNG JSON: {\"sentiment\": \"TICH_CUC|TRUNG_LAP|TIEU_CUC\", \"flags\": [\"...\"], \"needsAttention\": true/false, \"suggestedReplies\": [\"câu 1\", \"câu 2\", \"câu 3\"]}";
    String userPrompt =
        String.format(
            "Khách hàng: %s\nSản phẩm: %s\nSố sao: %d/5\nNội dung đánh giá: \"%s\"",
            customer, product, stars, comment);

    var completion = callLlm(systemPrompt, userPrompt);
    if (completion.isPresent()) {
      var parsed = parseJsonObject(completion.get().content());
      if (parsed != null && parsed.has("suggestedReplies")) {
        List<String> replies = readStringList(parsed.get("suggestedReplies"), 3);
        if (!replies.isEmpty()) {
          String sentiment = parsed.path("sentiment").asText(stars >= 4 ? "TICH_CUC" : stars == 3 ? "TRUNG_LAP" : "TIEU_CUC");
          var mergedFlags = new LinkedHashSet<>(localFlags);
          mergedFlags.addAll(readStringList(parsed.get("flags"), 5));
          boolean needsAttention = parsed.path("needsAttention").asBoolean(stars <= 2 || !mergedFlags.isEmpty());
          return Map.of(
              "reviewId", reviewId,
              "sentiment", sentiment,
              "flags", new ArrayList<>(mergedFlags),
              "needsAttention", needsAttention,
              "suggestedReplies", replies,
              "modelUsed", completion.get().modelUsed(),
              "usedRemote", true);
        }
      }
    }

    String sentiment = stars >= 4 ? "TICH_CUC" : stars == 3 ? "TRUNG_LAP" : "TIEU_CUC";
    boolean needsAttention = stars <= 2 || !localFlags.isEmpty();
    List<String> fallbackReplies =
        stars >= 4
            ? List.of(
                "YoFaSo cảm ơn " + customer + " đã yêu thích " + product + "! Rất mong được đồng hành cùng bạn trong những set đồ tiếp theo.",
                "Cảm ơn bạn đã dành đánh giá " + stars + " sao cho YoFaSo. Đừng quên dùng tính năng AI VTON để phối thêm các món mới nhé!",
                "YoFaSo rất vui vì sản phẩm làm bạn hài lòng. Cảm ơn bạn đã tin tưởng lựa chọn shop!")
            : List.of(
                "YoFaSo chân thành xin lỗi " + customer + " vì trải nghiệm chưa trọn vẹn với " + product + ". Shop đã ghi nhận và xin phép nhắn tin trực tiếp để hỗ trợ đổi trả/khắc phục ngay cho bạn ạ.",
                "Cảm ơn góp ý thẳng thắn của bạn về " + product + ". Bạn vui lòng nhắn Live Chat hoặc hotline 0795 605 214 để YoFaSo hỗ trợ đổi size/đổi sản phẩm trong 14 ngày hoàn toàn miễn phí nhé.");
    return Map.of(
        "reviewId", reviewId,
        "sentiment", sentiment,
        "flags", localFlags,
        "needsAttention", needsAttention,
        "suggestedReplies", fallbackReplies,
        "modelUsed", "local-policy-fallback",
        "usedRemote", false);
  }

  public Map<String, Object> suggestChatReplies(String customerId) {
    var customer =
        db.one(
            "SELECT MaNguoiDung AS userId, HoVaTen AS fullName, HangThanhVien AS membership"
                + " FROM NguoiDung WHERE MaNguoiDung=?",
            customerId.trim());
    var messages =
        db.rows(
            "SELECT VaiTroNguoiGui AS sender, NoiDung AS content, ThoiGianGui AS sentAt"
                + " FROM TinNhanTrucTuyen"
                + " WHERE MaNguoiDung=? AND KenhTroChuyen='CHU_SHOP'"
                + " ORDER BY ThoiGianGui DESC LIMIT 8",
            customerId.trim());
    Collections.reverse(messages);

    var recentOrder =
        db.rows(
            "SELECT MaDonHang AS orderId, TrangThaiDonHang AS status, TrangThaiThanhToan AS paymentStatus"
                + " FROM DonHang WHERE MaNguoiDung=? ORDER BY NgayTao DESC LIMIT 1",
            customerId.trim());
    var recentVton =
        db.rows(
            "SELECT p.MaPhienThuDo AS sessionId, s.TenSanPham AS productName, p.DiemTongThe AS score"
                + " FROM PhienThuDoAo p JOIN BienTheSanPham b ON b.MaBienThe=p.MaBienThe"
                + " JOIN SanPham s ON s.MaSanPham=b.MaSanPham"
                + " WHERE p.MaNguoiDung=? ORDER BY p.ThoiGianKhoiTao DESC LIMIT 1",
            customerId.trim());

    StringBuilder convo = new StringBuilder();
    for (var m : messages) {
      convo.append(Db.text(m, "sender")).append(": ").append(Db.text(m, "content")).append("\n");
    }
    String orderCtx = recentOrder.isEmpty()
        ? "Chưa có đơn hàng gần đây"
        : "Đơn gần nhất #" + recentOrder.getFirst().get("orderId") + " (" + recentOrder.getFirst().get("status") + ", " + recentOrder.getFirst().get("paymentStatus") + ")";
    String vtonCtx = recentVton.isEmpty()
        ? "Chưa có phiên thử đồ VTON"
        : "Phiên VTON gần nhất #" + recentVton.getFirst().get("sessionId") + " thử " + recentVton.getFirst().get("productName") + " (điểm " + recentVton.getFirst().get("score") + ")";

    String systemPrompt =
        "Bạn là trợ lý gợi ý trả lời nhanh cho Chủ shop YoFaSo trong khung Live Chat CSKH. "
            + "Dựa vào lịch sử hội thoại và ngữ cảnh đơn hàng/VTON, hãy tóm tắt ý định khách hàng (1 câu ngắn) và đề xuất 3 câu trả lời tiếng Việt lịch sự, thực tế (mỗi câu <= 180 ký tự) để Chủ shop bấm chèn vào ô nhập. "
            + "Trả về ĐÚNG JSON: {\"intentSummary\": \"...\", \"suggestedReplies\": [\"...\", \"...\", \"...\"]}";
    String userPrompt =
        String.format(
            "Khách: %s (Hạng %s)\nNgữ cảnh đơn: %s\nNgữ cảnh VTON: %s\nHội thoại gần nhất:\n%s",
            Db.text(customer, "fullName"),
            Db.text(customer, "membership"),
            orderCtx,
            vtonCtx,
            convo.isEmpty() ? "(Khách vừa mở khung chat)" : convo.toString());

    var completion = callLlm(systemPrompt, userPrompt);
    if (completion.isPresent()) {
      var parsed = parseJsonObject(completion.get().content());
      if (parsed != null && parsed.has("suggestedReplies")) {
        List<String> replies = readStringList(parsed.get("suggestedReplies"), 3);
        if (!replies.isEmpty()) {
          return Map.of(
              "customerId", customerId,
              "intentSummary", parsed.path("intentSummary").asText("Hỗ trợ tư vấn đơn hàng và phối đồ"),
              "suggestedReplies", replies,
              "modelUsed", completion.get().modelUsed(),
              "usedRemote", true);
        }
      }
    }

    String fullName = Db.text(customer, "fullName");
    return Map.of(
        "customerId", customerId,
        "intentSummary", orderCtx + " · " + vtonCtx,
        "suggestedReplies", List.of(
            "Chào " + fullName + ", YoFaSo đã tiếp nhận tin nhắn của bạn! Bạn cần shop kiểm tra size, phối đồ VTON hay tình trạng đơn hàng ạ?",
            recentOrder.isEmpty()
                ? "Bạn có thể dùng tính năng AI VTON thử trực tiếp trên app, shop hỗ trợ đổi size miễn phí trong 14 ngày ạ."
                : "Shop đã kiểm tra đơn #" + recentOrder.getFirst().get("orderId") + " của bạn, hiện trạng thái là " + recentOrder.getFirst().get("status") + ". Shop sẽ theo dõi sát để giao sớm nhất ạ!",
            "Nếu bạn ghé showroom thử trực tiếp, chỉ cần đọc mã phiên VTON hoặc SĐT tại quầy để thu ngân lấy đúng kệ hàng cho bạn nhé!"),
        "modelUsed", "local-cs-fallback",
        "usedRemote", false);
  }

  public Map<String, Object> executiveSummary(boolean forceRefresh) {
    long now = System.currentTimeMillis();
    if (!forceRefresh && cachedExecutiveSummary != null && (now - cachedExecutiveAtMs) < SUMMARY_CACHE_TTL_MS) {
      var copy = new LinkedHashMap<>(cachedExecutiveSummary);
      copy.put("cached", true);
      return copy;
    }

    var finance = staff.financeReport();
    var lowStock = staff.lowStock(5, 0, 10);
    var vton = staff.vtonReport(30);
    long pendingReviews = db.count("SELECT COUNT(*) FROM DanhGiaSanPham WHERE TrangThaiDuyet='CHO_DUYET'");
    long pendingShifts = db.count("SELECT COUNT(*) FROM CaLamViecThuNgan WHERE TrangThaiCa='DA_DONG_CHO_DUYET'");
    long pendingOrders = db.count("SELECT COUNT(*) FROM DonHang WHERE TrangThaiDonHang='CHO_XAC_NHAN'");

    BigDecimal completedRevenue =
        Db.money(
            db.one(
                "SELECT COALESCE(SUM(COALESCE(ct.subtotal,0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total"
                    + " FROM DonHang d LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang"
                    + " WHERE d.TrangThaiDonHang='HOAN_TAT'"),
            "total");

    StringBuilder lowStockText = new StringBuilder();
    for (var item : lowStock) {
      lowStockText.append(String.format("- %s (%s/%s): còn %s cái (kệ %s)\n",
          item.getOrDefault("sku", item.get("variantId")),
          item.getOrDefault("colorName", ""),
          item.getOrDefault("size", ""),
          item.getOrDefault("stock", 0),
          item.getOrDefault("shelf", "KHO")));
    }

    String systemPrompt =
        "Bạn là giám đốc vận hành bán lẻ AI cho YoFaSo Shop. "
            + "Dựa CHÍNH XÁC vào số liệu thật được cung cấp (không bịa thêm con số), hãy tóm tắt tình hình kinh doanh và đưa ra hành động ưu tiên bằng tiếng Việt. "
            + "Trả về ĐÚNG JSON: {\"summaryBullets\": [\"3-5 gạch đầu dòng súc tích\"], \"urgentAlerts\": [\"các việc cần xử lý ngay\"], \"inventoryActions\": [\"khuyến nghị nhập/cân kho\"]}";
    String userPrompt =
        String.format(
            "Tổng doanh thu hoàn tất: %s VND\nTổng đơn: %s (Hoàn tất: %s, Trả hàng: %s, Tỉ lệ hoàn: %s%%)\n"
                + "Đơn chờ xác nhận: %d | Đánh giá chờ duyệt: %d | Ca POS chờ duyệt: %d\n"
                + "AI VTON 30 ngày: %s phiên (%s thành công, điểm TB %s)\n"
                + "Top SKU tồn kho thấp (<=5):\n%s",
            completedRevenue.setScale(0, RoundingMode.HALF_UP),
            finance.get("totalOrders"),
            finance.get("completedOrders"),
            finance.get("returnedOrders"),
            finance.get("returnRate"),
            pendingOrders,
            pendingReviews,
            pendingShifts,
            vton.get("totalSessions"),
            vton.get("completedSessions"),
            vton.get("avgScore"),
            lowStockText.isEmpty() ? "(Không có SKU nào dưới ngưỡng 5)" : lowStockText.toString());

    Map<String, Object> result;
    var completion = callLlm(systemPrompt, userPrompt);
    if (completion.isPresent()) {
      var parsed = parseJsonObject(completion.get().content());
      if (parsed != null && parsed.has("summaryBullets")) {
        List<String> bullets = readStringList(parsed.get("summaryBullets"), 5);
        if (!bullets.isEmpty()) {
          result = new LinkedHashMap<>();
          result.put("generatedAt", Instant.now().toString());
          result.put("summaryBullets", bullets);
          result.put("urgentAlerts", readStringList(parsed.get("urgentAlerts"), 5));
          result.put("inventoryActions", readStringList(parsed.get("inventoryActions"), 5));
          result.put("lowStockCount", lowStock.size());
          result.put("pendingReviews", pendingReviews);
          result.put("pendingShifts", pendingShifts);
          result.put("pendingOrders", pendingOrders);
          result.put("modelUsed", completion.get().modelUsed());
          result.put("usedRemote", true);
          result.put("cached", false);
          cachedExecutiveSummary = result;
          cachedExecutiveAtMs = now;
          return result;
        }
      }
    }

    var bullets = new ArrayList<String>();
    bullets.add("Doanh thu hoàn tất ghi nhận " + completedRevenue.setScale(0, RoundingMode.HALF_UP) + "đ trên " + finance.get("completedOrders") + "/" + finance.get("totalOrders") + " đơn hàng (tỉ lệ hoàn " + finance.get("returnRate") + "%).");
    bullets.add("Phòng thử đồ AI VTON 30 ngày đạt " + vton.get("completedSessions") + "/" + vton.get("totalSessions") + " phiên thành công, điểm thẩm mỹ trung bình " + vton.get("avgScore") + "/100.");
    bullets.add("Hiện có " + lowStock.size() + " SKU chạm ngưỡng tồn kho thấp (<= 5 cái) cần lên kế hoạch nhập bổ sung.");

    var alerts = new ArrayList<String>();
    if (pendingOrders > 0) alerts.add("Có " + pendingOrders + " đơn hàng online đang chờ xác nhận/đối soát.");
    if (pendingReviews > 0) alerts.add("Có " + pendingReviews + " đánh giá khách hàng đang chờ duyệt hiển thị.");
    if (pendingShifts > 0) alerts.add("Có " + pendingShifts + " ca làm việc POS đã kết ca chờ chủ shop phê duyệt két tiền.");
    if (alerts.isEmpty()) alerts.add("Không có đầu việc tồn đọng khẩn cấp tại thời điểm kiểm tra.");

    var invActions = new ArrayList<String>();
    for (int i = 0; i < Math.min(3, lowStock.size()); i++) {
      var item = lowStock.get(i);
      invActions.add("Ưu tiên nhập bổ sung SKU " + item.getOrDefault("sku", item.get("variantId")) + " (tồn hiện tại: " + item.getOrDefault("stock", 0) + ").");
    }
    if (invActions.isEmpty()) {
      invActions.add("Tồn kho các SKU hiện ổn định trên ngưỡng cảnh báo 5 sản phẩm.");
    }

    result = new LinkedHashMap<>();
    result.put("generatedAt", Instant.now().toString());
    result.put("summaryBullets", bullets);
    result.put("urgentAlerts", alerts);
    result.put("inventoryActions", invActions);
    result.put("lowStockCount", lowStock.size());
    result.put("pendingReviews", pendingReviews);
    result.put("pendingShifts", pendingShifts);
    result.put("pendingOrders", pendingOrders);
    result.put("modelUsed", "local-kpi-summary");
    result.put("usedRemote", false);
    result.put("cached", false);
    cachedExecutiveSummary = result;
    cachedExecutiveAtMs = now;
    return result;
  }

  private Optional<OpenRouterClient.CompletionResult> callLlm(String systemPrompt, String userPrompt) {
    if (llm == null || !llm.isConfigured()) return Optional.empty();
    try {
      List<Map<String, Object>> messages =
          List.of(
              Map.of("role", "system", "content", systemPrompt),
              Map.of("role", "user", "content", userPrompt));
      return llm.complete(messages, null);
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private JsonNode parseJsonObject(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      Matcher m = JSON_BLOCK.matcher(raw);
      if (m.find()) {
        return json.readTree(m.group());
      }
      return json.readTree(raw);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static List<String> readStringList(JsonNode node, int max) {
    if (node == null || !node.isArray()) return List.of();
    var out = new ArrayList<String>();
    for (JsonNode item : node) {
      String text = item.asText("").trim();
      if (!text.isBlank()) out.add(text);
      if (out.size() >= max) break;
    }
    return out;
  }

  private static List<String> defaultHighlights(String material, String fit, String pattern) {
    return List.of(
        "Chất liệu: " + material,
        "Phom dáng chuẩn (" + fit + "), họa tiết " + pattern,
        "Hỗ trợ ướm thử ảo AI VTON và chấm điểm phối đồ 40/40/20",
        "Đổi trả linh hoạt trong 14 ngày tại toàn hệ thống YoFaSo");
  }

  private static List<String> detectLocalReviewFlags(String comment) {
    if (comment == null || comment.isBlank()) return List.of();
    String lower = comment.toLowerCase(Locale.ROOT);
    var flags = new ArrayList<String>();
    if (lower.contains("http://") || lower.contains("https://") || lower.contains("zalo.me") || lower.contains("t.me")) {
      flags.add("Chứa liên kết ngoài / nghi vấn spam quảng cáo");
    }
    if (lower.contains("lừa đảo") || lower.contains("rác") || lower.contains("tệ hại") || lower.contains("rách") || lower.contains("lỗi nặng")) {
      flags.add("Cảnh báo phản hồi tiêu cực / cần CSKH xử lý gấp");
    }
    if (lower.matches(".*\\b0\\d{9,10}\\b.*")) {
      flags.add("Chứa số điện thoại cá nhân trong bình luận công khai");
    }
    return flags;
  }

  private static String safe(String s, String fallback) {
    return s == null || s.isBlank() ? fallback : s.trim();
  }
}
