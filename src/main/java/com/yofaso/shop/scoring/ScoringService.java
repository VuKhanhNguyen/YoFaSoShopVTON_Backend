package com.yofaso.shop.scoring;

import com.yofaso.shop.chat.OpenRouterClient;
import com.yofaso.shop.common.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Điểm quy tắc 40/40/20 từ catalog + lớp AI Stylist (Vision/Text) viết lại lời nhận xét chỉn chu. */
@Service
public class ScoringService {
  private static final Logger log = LoggerFactory.getLogger(ScoringService.class);
  private static final Pattern HEX_CODE = Pattern.compile("#[A-Fa-f0-9]{6}");
  private static final Pattern HEX_IN_PARENS = Pattern.compile("\\s*\\(#[A-Fa-f0-9]{6}\\)");

  private final Db db;
  private final JsonNode workbook;
  private final OpenRouterClient llm;

  /** Tương thích kiểm thử không dùng LLM. */
  public ScoringService(Db db, JsonMapper mapper) throws java.io.IOException {
    this(db, mapper, null);
  }

  /** Nạp bản trích nguyên workbook có nguồn, không sửa Excel gốc. */
  @Autowired
  public ScoringService(Db db, JsonMapper mapper, OpenRouterClient llm) throws java.io.IOException {
    this.db = db;
    this.llm = llm;
    try (var input = getClass().getResourceAsStream("/scoring/workbook-rules.json")) {
      if (input == null) {
        throw new IllegalStateException("Không tìm thấy file tài nguyên /scoring/workbook-rules.json trên classpath.");
      }
      workbook = mapper.readTree(input);
    }
  }

  /** Danh sách quy tắc để UI và người test đối chiếu file Excel. */
  public JsonNode rules() {
    return workbook;
  }

  /** Lấy feature do chủ shop duyệt và màu theo SKU, không lấy thuộc tính từ client. */
  public Map<String, Object> garment(String variant) {
    return db.one(
        "SELECT p.*,b.MaBienThe,b.TenMau,b.MaMauSac FROM SanPham p JOIN BienTheSanPham b ON"
            + " b.MaSanPham=p.MaSanPham WHERE b.MaBienThe=? AND"
            + " p.TrangThaiSanPham='DANG_KINH_DOANH' AND b.TrangThai<>'NGUNG_BAN'",
        variant);
  }

  /** Chấm một set áo/quần hoặc đầm bằng công thức 40/40/20 deterministic từ catalog. */
  public Map<String, Object> evaluate(String first, String second, boolean tucked) {
    var a = garment(first);
    Map<String, Object> b = second == null ? null : garment(second);
    String category = Db.text(a, "PhanLoaiVTON");
    if (category.equals("lower_body") && b != null) {
      var temp = a;
      a = b;
      b = temp;
      category = Db.text(a, "PhanLoaiVTON");
    }
    boolean dress = category.equals("dress");
    Problem.require(
        dress
            ? b == null
            : b != null
                && category.equals("upper_body")
                && "lower_body".equals(b.get("PhanLoaiVTON")),
        422,
        "OUTFIT_INVALID",
        "Chọn một đầm hoặc một áo và một quần/chân váy.");
    String prop = proportion(a, b, tucked);
    var propRow = ruleRow(1, prop);
    int proportion = Integer.parseInt(propRow.get(5).asText());
    List<String> colors = parseHexList(Db.text(a, "MaMauSac"));
    if (b != null) colors.addAll(parseHexList(Db.text(b, "MaMauSac")));
    var colorEval = evaluateColors(colors);
    int color = colorEval.score();
    String pa = Db.text(a, "LoaiHoaTiet"), pb = b == null ? "VAI_TRON" : Db.text(b, "LoaiHoaTiet");
    String patternRule = pattern(pa, pb);
    var patRow = ruleRow(4, patternRule);
    int pattern = Integer.parseInt(patRow.get(3).asText());
    var penalties = new ArrayList<String>();
    if (prop.equals("PROP_09") || prop.equals("PROP_10")) penalties.add("PEN_03");
    if (prop.equals("PROP_11") || prop.equals("PROP_12")) penalties.add("PEN_07");
    if (pa.equals("HOA_TO_BAN") && pb.equals("HOA_TO_BAN")) penalties.add("PEN_06");
    List<float[]> hsv = colors.stream().map(ScoringService::hsv).toList();
    boolean neon =
        colors.stream()
            .anyMatch(c -> Set.of("#39FF14", "#FF1493", "#CCFF00", "#FF5F1F").contains(c));
    if (neon && hsv.stream().filter(h -> h[1] > .8f).count() >= 2) penalties.add("PEN_05");
    if (!neon && hsv.size() == 2 && hsv.get(0)[1] > .8 && hsv.get(1)[1] > .8) {
      double angle = Math.abs(hsv.get(0)[0] - hsv.get(1)[0]) * 360;
      angle = Math.min(angle, 360 - angle);
      if (angle >= 30 && angle <= 60) penalties.add("PEN_04");
    }
    if (color >= 60 && color <= 69 && hsv.stream().allMatch(h -> h[1] < .4 && h[2] < .65))
      penalties.add("PEN_01");
    var penaltyNotes = new ArrayList<String>();
    int penalty = 0;
    for (String pCode : penalties) {
      var penRow = ruleRow(5, pCode);
      penalty += Math.abs(Integer.parseInt(penRow.get(2).asText()));
      penaltyNotes.add(cleanRuleText(penRow.get(3).asText()));
    }
    int total =
        Math.max(
            0,
            Math.min(100, (int) Math.round(.4 * proportion + .4 * color + .2 * pattern - penalty)));
    String tier = tier(total);
    var tierNode = tierRow(tier);
    String tierLabel = cleanRuleText(tierNode.get(2).asText());
    String tierMeaning = cleanRuleText(tierNode.get(4).asText());
    String formula = String.format("S = 0.4·%d + 0.4·%d + 0.2·%d - %d", proportion, color, pattern, penalty);
    String propNote = cleanRuleText(propRow.get(6).asText());
    String colNote = colorEval.note();
    String patNote = cleanRuleText(patRow.get(4).asText());
    String penaltyNote = penaltyNotes.isEmpty() ? "" : String.join(" ", penaltyNotes);
    var recommendedColors = suggestHarmonyColors(colors);
    String stylistComment = buildCleanFallbackStylistNote(
        total, tier, tierMeaning, propNote, colNote, patNote, penaltyNote, recommendedColors);

    var breakdown = Map.of(
        "proportion", Map.of("score", proportion, "weight", "40%", "ruleCode", prop, "ruleName", cleanRuleText(propRow.get(1).asText()), "stylistNote", propNote),
        "color", Map.of("score", color, "weight", "40%", "stylistNote", colNote, "swatches", colorEval.swatches()),
        "pattern", Map.of("score", pattern, "weight", "20%", "ruleCode", patternRule, "stylistNote", patNote),
        "penalty", Map.of("score", penalty, "rules", penalties, "stylistNote", penaltyNote)
    );

    var out = new LinkedHashMap<String, Object>();
    out.put("total", total);
    out.put("proportion", proportion);
    out.put("color", color);
    out.put("pattern", pattern);
    out.put("penalty", penalty);
    out.put("tier", tier);
    out.put("tierLabel", tierLabel);
    out.put("formula", formula);
    out.put("breakdown", breakdown);
    out.put("colorSwatches", colorEval.swatches());
    out.put("recommendedColors", recommendedColors);
    out.put("stylistComment", stylistComment);
    out.put("aiModelSource", "WORKBOOK_RULES_JSON");
    out.put("proportionRule", prop);
    out.put("patternRule", patternRule);
    out.put("penaltyRules", penalties);
    out.put("source", workbook.path("source").asText("ScoringTable.xlsx"));
    out.put("assessmentType", "CATALOG_RULE_ESTIMATE");
    out.put(
        "assumptions",
        List.of(
            "Sơ vin do người dùng khai báo, không suy ra từ ảnh.",
            "Màu gần nhất trong bảng RGB; màu ngoài bảng được xấp xỉ, không coi là đo màu chuẩn.",
            "Chưa chấm phụ kiện, chất liệu gân, họa tiết chìm do catalog chưa có field tương ứng."));
    out.put("advice", stylistComment);
    return out;
  }

  /**
   * AI Stylist sau VTON: giữ nguyên 100% điểm số 40/40/20 từ rule engine và dùng tuyến Vision
   * (gemini -> nvidia 4 model vision -> openrouter, tự fallback sang text nếu không có ảnh/Vision bận)
   * để viết lại lời nhận xét tinh tế, chỉn chu, lời hay ý đẹp cho khách hàng.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> enhanceStylistEvaluation(
      Map<String, Object> baseEval,
      String firstVariantId,
      String secondVariantId,
      boolean tucked,
      byte[] vtonResultImage) {
    var result = new LinkedHashMap<String, Object>(baseEval);
    if (llm == null || !llm.isConfigured()) {
      return result;
    }

    try {
      String firstDesc = describeGarmentSafe(firstVariantId);
      String secondDesc = secondVariantId == null || secondVariantId.isBlank()
          ? ""
          : describeGarmentSafe(secondVariantId);

      var breakdown = baseEval.get("breakdown") instanceof Map<?, ?> bm ? (Map<String, Object>) bm : Map.<String, Object>of();
      String propNote = nestedNote(breakdown, "proportion");
      String colNote = nestedNote(breakdown, "color");
      String patNote = nestedNote(breakdown, "pattern");
      String penNote = nestedNote(breakdown, "penalty");

      var recColors = baseEval.get("recommendedColors") instanceof List<?> list
          ? list.stream()
              .filter(Map.class::isInstance)
              .map(m -> Objects.toString(((Map<?, ?>) m).get("name"), ""))
              .filter(s -> !s.isBlank())
              .toList()
          : List.<String>of();

      String imageDataUrl = toVisionDataUrl(vtonResultImage);
      boolean withImage = imageDataUrl != null;

      String systemPrompt = """
          Bạn là Stylist Thời trang cao cấp của thương hiệu YoFaSo Atelier.
          Nhiệm vụ: Viết lại lời nhận xét thẩm mỹ cho bộ trang phục khách vừa thử đồ ảo (VTON) thành 2 đến 4 câu tiếng Việt tự nhiên, ấm áp, tinh tế và chỉn chu (tối đa khoảng 95 từ).
          QUY TẮC BẮT BUỘC:
          - Giữ đúng tinh thần điểm số hệ thống đã chấm (từ 85 trở lên: khen tôn dáng và gợi ý phụ kiện/giày hoàn thiện; 70-84: khen điểm sáng và gợi ý tinh chỉnh nhẹ để lên tầm xuất sắc; dưới 70: góp ý thật khéo léo, tích cực và khuyên đổi món phối hợp hơn).
          - Dùng từ ngữ thời trang tinh tế, gần gũi (phom dáng, đường cắt, độ rủ, hiệu ứng kéo dài đôi chân, sự chuyển sắc hài hòa, điểm nhấn phụ kiện).
          - Nếu có nhắc màu sắc gợi ý, CHỈ dùng tên màu tiếng Việt (như Trắng, Be kem, Xanh navy, Nâu đất...), TUYỆT ĐỐI KHÔNG viết mã màu HEX kiểu #FFFFFF hay #1B2A4A.
          - TUYỆT ĐỐI KHÔNG nhắc công thức toán học (0.4*...), không nhắc mã quy tắc nội bộ (PROP_01, WORKBOOK, SMART CROSS-SELLING, catalog rule), không dùng gạch đầu dòng hay markdown, chỉ trả về 1 đoạn văn duy nhất.
          """;

      var userPrompt = new StringBuilder();
      userPrompt.append("DỮ LIỆU SET ĐỒ KHÁCH VỪA THỬ:\n");
      userPrompt.append("- Món chính: ").append(firstDesc).append("\n");
      if (!secondDesc.isBlank()) {
        userPrompt.append("- Món phối kèm: ").append(secondDesc).append("\n");
      }
      userPrompt.append("- Cách mặc: ").append(tucked ? "Sơ vin gọn gàng" : "Thả suông tự nhiên").append("\n");
      userPrompt.append("- Điểm tổng: ").append(baseEval.get("total")).append("/100 (")
          .append(baseEval.get("tierLabel")).append(")\n");
      userPrompt.append("- Tỉ lệ vóc dáng (").append(baseEval.get("proportion")).append("/100): ").append(propNote).append("\n");
      userPrompt.append("- Hòa hợp màu sắc (").append(baseEval.get("color")).append("/100): ").append(colNote).append("\n");
      userPrompt.append("- Cân bằng họa tiết (").append(baseEval.get("pattern")).append("/100): ").append(patNote).append("\n");
      if (!penNote.isBlank()) {
        userPrompt.append("- Điểm cần lưu ý: ").append(penNote).append("\n");
      }
      if (!recColors.isEmpty()) {
        userPrompt.append("- Tông màu gợi ý phối thêm (hiển thị dạng swatch bên dưới): ")
            .append(String.join(", ", recColors)).append("\n");
      }
      if (withImage) {
        userPrompt.append("- Ảnh kết quả thử đồ thật được đính kèm: hãy quan sát thêm tổng thể dáng lên người và sắc độ trang phục trong ảnh để nhận xét sinh động, sát thực tế hơn.\n");
      }
      userPrompt.append("\nHãy viết ngay đoạn nhận xét Stylist dành cho khách hàng:");

      List<Map<String, Object>> messages = List.of(
          Map.of("role", "system", "content", systemPrompt),
          Map.of("role", "user", "content", userPrompt.toString()));

      Optional<OpenRouterClient.CompletionResult> remote = llm.complete(messages, imageDataUrl);
      if (remote.isPresent()) {
        String polished = sanitizeStylistOutput(remote.get().content());
        if (!polished.isBlank()) {
          result.put("stylistComment", polished);
          result.put("advice", polished);
          result.put("aiModelSource", (withImage ? "AI_STYLIST_VISION:" : "AI_STYLIST_TEXT:") + remote.get().modelUsed());
          result.put("stylistEnhanced", true);
        }
      }
    } catch (Exception e) {
      log.warn("AI Stylist remake skipped due to error ({}); keeping clean rule-based comment.", e.getMessage());
    }
    return result;
  }

  /** Thông tin đơn lẻ từ workbook-rules.json khi khách mới thử 1 món upper/lower chưa ghép set. */
  public Map<String, Object> singleItemPreview(String variantId) {
    var g = garment(variantId);
    List<String> colors = parseHexList(Db.text(g, "MaMauSac"));
    var colorEval = evaluateColors(colors);
    String pa = Db.text(g, "LoaiHoaTiet");
    String patternRule = pattern(pa, "VAI_TRON");
    var patRow = ruleRow(4, patternRule);
    var out = new LinkedHashMap<String, Object>();
    out.put("evaluated", false);
    out.put("singleItem", true);
    out.put("colorName", Db.text(g, "TenMau"));
    out.put("colorNote", colorEval.note());
    out.put("colorSwatches", colorEval.swatches());
    out.put("recommendedColors", suggestHarmonyColors(colors));
    out.put("patternRule", patternRule);
    out.put("patternNote", cleanRuleText(patRow.get(4).asText()));
    out.put("source", workbook.path("source").asText("ScoringTable.xlsx"));
    return out;
  }

  /** Làm giàu thông tin hiển thị từ workbook-rules.json cho phiên đã lưu điểm. */
  public Map<String, Object> enrichSavedSession(String tier, String propRule) {
    return enrichSavedSession(tier, propRule, null, null);
  }

  /** Làm giàu thêm colorSwatches + recommendedColors cho phiên đã lưu điểm để UI hiện swatch chuẩn. */
  public Map<String, Object> enrichSavedSession(
      String tier, String propRule, String firstVariantId, String secondVariantId) {
    var out = new LinkedHashMap<String, Object>();
    if (tier != null && !tier.isBlank()) {
      var t = tierRow(tier);
      if (t != null) out.put("tierLabel", cleanRuleText(t.get(2).asText()));
    }
    if (propRule != null && !propRule.isBlank()) {
      try {
        var p = ruleRow(1, propRule);
        out.put("proportionNote", cleanRuleText(p.get(6).asText()));
      } catch (IllegalStateException ignored) {}
    }
    if (firstVariantId != null && !firstVariantId.isBlank()) {
      try {
        var colors = new ArrayList<String>();
        var a = garment(firstVariantId);
        colors.addAll(parseHexList(Db.text(a, "MaMauSac")));
        if (secondVariantId != null && !secondVariantId.isBlank()) {
          var b = garment(secondVariantId);
          colors.addAll(parseHexList(Db.text(b, "MaMauSac")));
        }
        if (!colors.isEmpty()) {
          var colorEval = evaluateColors(colors);
          out.put("colorNote", colorEval.note());
          out.put("colorSwatches", colorEval.swatches());
          out.put("recommendedColors", suggestHarmonyColors(colors));
        }
      } catch (Exception ignored) {}
    }
    return out;
  }

  /** Chọn một trong 12 quy tắc tỷ lệ; fallback casual được công khai như ước lượng. */
  private String proportion(Map<String, Object> a, Map<String, Object> b, boolean tucked) {
    if (b == null) {
      String waist = Db.text(a, "LoaiCapQuan");
      Problem.require(
          "CAP_CAO".equals(waist) || "KHONG_AP_DUNG".equals(waist) || waist.isBlank(),
          422,
          "SCORING_METADATA_INCOMPLETE",
          "Đầm cần xác nhận chiết eo cao trước khi dùng PROP_06.");
      return "PROP_06";
    }
    String length = Db.text(a, "ChieuDaiTrangPhuc"),
        waist = Db.text(b, "LoaiCapQuan"),
        subtype = Db.text(b, "TieuLoaiTrangPhuc");
    boolean high = waist.equals("CAP_CAO"),
        low = waist.equals("CAP_TRE"),
        longTop = length.equals("AO_DANG_DAI"),
        shortBottom = Db.text(b, "ChieuDaiTrangPhuc").equals("NGAN_TREN_GOI");
    if (high && length.equals("AO_CROPTOP"))
      return subtype.contains("skirt") ? "PROP_03" : "PROP_02";
    if (high && tucked) return "PROP_01";
    if (longTop && shortBottom) return subtype.contains("skirt") ? "PROP_04" : "PROP_05";
    if (longTop
        && low
        && "RONG_OVERSIZE".equals(a.get("KieuDang"))
        && "RONG_OVERSIZE".equals(b.get("KieuDang"))) return "PROP_11";
    if (longTop && low)
      return Db.text(b, "ChieuDaiTrangPhuc").equals("LUNG_NGANG_GOI") ? "PROP_10" : "PROP_09";
    return "PROP_07";
  }

  /** Mapping các pattern ERD có đủ dữ liệu; không đoán Jacquard/gân khi chưa có field. */
  private String pattern(String a, String b) {
    if (a.equals("VAI_TRON") && b.equals("VAI_TRON")) return "PAT_04";
    if (a.equals("HOA_TO_BAN") && b.equals("HOA_TO_BAN")) return "PAT_08";
    if (a.equals("KE_SOC_TAM") && b.equals("KE_SOC_TAM")) return "PAT_06";
    if (a.equals("VAI_TRON")) return "PAT_03";
    if (b.equals("VAI_TRON")) return a.startsWith("KE_") ? "PAT_02" : "PAT_01";
    return "PAT_07";
  }

  private record ColorEval(int score, String note, List<Map<String, Object>> swatches) {}

  private static String cleanRuleText(String text) {
    return text == null ? "" : text.replaceAll("\\s*\\n\\s*", " ").replaceAll("\\s{2,}", " ").trim();
  }

  /** Chuyển nhãn "Xanh Navy (#1B2A4A)" thành tên sạch "Xanh Navy" để khách không thấy mã HEX. */
  public static String stripHexCodes(String text) {
    if (text == null) return "";
    String withoutParens = HEX_IN_PARENS.matcher(text).replaceAll("");
    return HEX_CODE.matcher(withoutParens).replaceAll("").replaceAll("\\s{2,}", " ").trim();
  }

  private static Map<String, Object> toSwatch(String rawLabel) {
    String cleaned = cleanRuleText(rawLabel);
    String name = stripHexCodes(cleaned);
    var m = HEX_CODE.matcher(cleaned);
    String hex = m.find() ? m.group().toUpperCase(Locale.ROOT) : "#CBD5E1";
    var map = new LinkedHashMap<String, Object>();
    map.put("name", name.isBlank() ? hex : name);
    map.put("hex", hex);
    return map;
  }

  private static List<String> parseHexList(String raw) {
    var out = new ArrayList<String>();
    if (raw == null || raw.isBlank()) return out;
    for (String part : raw.toUpperCase(Locale.ROOT).split(",")) {
      String t = part.trim();
      if (HEX_CODE.matcher(t).matches()) out.add(t);
    }
    return out;
  }

  /** Gợi ý tối đa 4 tông màu phối hài hòa (>= 92đ trên bánh xe màu) kèm mã hex để UI vẽ swatch tròn. */
  public List<Map<String, Object>> suggestHarmonyColors(List<String> baseHexes) {
    if (baseHexes == null || baseHexes.isEmpty()) return List.of();
    var matrix = workbook.get("sheets").get(2);
    var existingRowIndices = new HashSet<Integer>();
    for (String c : baseHexes) {
      existingRowIndices.add(nearestMatrixRow(matrix, c));
    }

    record ScoredSwatch(Map<String, Object> swatch, int score) {}
    var candidates = new ArrayList<ScoredSwatch>();
    for (int col = 1; col <= 12; col++) {
      String colLabel = cleanRuleText(matrix.get(0).get(col).asText());
      String colHex = hex(colLabel).toUpperCase(Locale.ROOT);
      int colAsRow = nearestMatrixRow(matrix, colHex);
      if (existingRowIndices.contains(colAsRow)) continue;

      int sum = 0;
      for (int rowIdx : existingRowIndices) {
        sum += Integer.parseInt(matrix.get(rowIdx).get(col).asText());
      }
      int avg = Math.round((float) sum / existingRowIndices.size());
      if (avg >= 92) {
        var sw = toSwatch(colLabel);
        sw.put("score", avg);
        candidates.add(new ScoredSwatch(sw, avg));
      }
    }
    candidates.sort(Comparator.comparingInt(ScoredSwatch::score).reversed());
    return candidates.stream().limit(4).map(ScoredSwatch::swatch).toList();
  }

  private int nearestMatrixRow(JsonNode matrix, String targetHex) {
    int bestRow = 1;
    long bestDist = Long.MAX_VALUE;
    for (int i = 1; i < matrix.size(); i++) {
      long d = distance(targetHex, hex(matrix.get(i).get(0).asText()));
      if (d < bestDist) {
        bestDist = d;
        bestRow = i;
      }
    }
    return bestRow;
  }

  private static String buildCleanFallbackStylistNote(
      int total,
      String tier,
      String tierMeaning,
      String propNote,
      String colNote,
      String patNote,
      String penaltyNote,
      List<Map<String, Object>> recommendedColors) {
    StringBuilder sb = new StringBuilder();
    if (!tierMeaning.isBlank()) {
      sb.append(tierMeaning);
      if (!tierMeaning.endsWith(".")) sb.append(".");
    }
    if (!propNote.isBlank()) {
      sb.append(" ").append(propNote);
      if (!propNote.endsWith(".")) sb.append(".");
    }
    if (!penaltyNote.isBlank()) {
      sb.append(" Lưu ý nhỏ: ").append(penaltyNote);
      if (!penaltyNote.endsWith(".")) sb.append(".");
    }
    String tail = switch (tier) {
      case "RECOMMENDED" -> " Bạn có thể tự tin diện ngay set đồ này và phối thêm giày hoặc túi trung tính để tổng thể thêm cuốn hút.";
      case "ACCEPTABLE" -> " Tổng thể đã khá gọn gàng; bạn có thể nhấn nhẹ bằng phụ kiện hoặc thử đổi tông màu gợi ý bên dưới để nâng lên mức xuất sắc.";
      default -> " Bạn thử kết hợp với một trong các món đối ứng hoặc tông màu gợi ý bên dưới để cân bằng lại phom dáng và sắc độ hài hòa hơn nhé.";
    };
    sb.append(tail);
    return stripHexCodes(sb.toString());
  }

  static String sanitizeStylistOutput(String raw) {
    if (raw == null) return "";
    String cleaned = raw.trim()
        .replaceAll("^[“\"']+|[”\"']+$", "")
        .replaceAll("\\*\\*|__|`", "")
        .replaceAll("\\s*\\n+\\s*", " ");
    cleaned = stripHexCodes(cleaned);
    if (cleaned.length() > 900) {
      cleaned = cleaned.substring(0, 900).trim();
    }
    return cleaned;
  }

  private String describeGarmentSafe(String variantId) {
    try {
      var g = garment(variantId);
      String name = Db.text(g, "TenSanPham");
      String color = Db.text(g, "TenMau");
      String fit = Db.text(g, "KieuDang");
      String material = Db.text(g, "ChatLieu");
      var parts = new ArrayList<String>();
      parts.add(name.isBlank() ? variantId : name);
      if (!color.isBlank()) parts.add("màu " + color);
      if (!material.isBlank()) parts.add("chất liệu " + material);
      if (!fit.isBlank()) parts.add("form " + fit.toLowerCase(Locale.ROOT).replace('_', ' '));
      return String.join(", ", parts);
    } catch (Exception e) {
      return variantId;
    }
  }

  @SuppressWarnings("unchecked")
  private static String nestedNote(Map<String, Object> breakdown, String key) {
    Object obj = breakdown.get(key);
    if (obj instanceof Map<?, ?> m) {
      return Objects.toString(((Map<String, Object>) m).get("stylistNote"), "");
    }
    return "";
  }

  /** Thu gọn ảnh VTON về tối đa 768px dạng JPEG base64 để gửi Vision LLM nhanh & nhẹ. */
  static String toVisionDataUrl(byte[] rawBytes) {
    if (rawBytes == null || rawBytes.length == 0) return null;
    try {
      BufferedImage src = ImageIO.read(new ByteArrayInputStream(rawBytes));
      if (src == null) return null;
      int w = src.getWidth(), h = src.getHeight();
      int maxSide = 768;
      int tw = w, th = h;
      if (Math.max(w, h) > maxSide) {
        double scale = (double) maxSide / Math.max(w, h);
        tw = Math.max(1, (int) Math.round(w * scale));
        th = Math.max(1, (int) Math.round(h * scale));
      }
      BufferedImage rgb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = rgb.createGraphics();
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, tw, th);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(src, 0, 0, tw, th, null);
      g.dispose();

      var baos = new ByteArrayOutputStream();
      ImageIO.write(rgb, "jpg", baos);
      return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
    } catch (Exception e) {
      return null;
    }
  }

  private JsonNode ruleRow(int sheet, String code) {
    for (var row : workbook.get("sheets").get(sheet))
      if (row.get(0).asText().equals(code)) return row;
    throw new IllegalStateException("Rule missing: " + code);
  }

  private JsonNode tierRow(String tierCode) {
    for (var row : workbook.get("sheets").get(0))
      if (row.get(1).asText().equals(tierCode)) return row;
    return workbook.get("sheets").get(0).get(1);
  }

  /** Màu một món không có cặp đối chiếu được tính như cùng màu trong matrix; đọc nhãn từ JSON và tách Swatch. */
  private ColorEval evaluateColors(List<String> colors) {
    var unique = colors.stream().distinct().sorted().toList();
    if (unique.size() >= 3) {
      for (var row : workbook.get("sheets").get(3)) {
        if (!row.get(0).asText().startsWith("MULTI_")) continue;
        var set = new TreeSet<String>();
        var multiSwatches = new ArrayList<Map<String, Object>>();
        for (int i = 2; i <= 5; i++) {
          String cell = cleanRuleText(row.get(i).asText());
          var m = HEX_CODE.matcher(cell);
          if (m.find()) {
            set.add(m.group().toUpperCase(Locale.ROOT));
            multiSwatches.add(toSwatch(cell));
          }
        }
        if (set.equals(new TreeSet<>(unique))) {
          return new ColorEval(
              Integer.parseInt(row.get(7).asText()),
              stripHexCodes(cleanRuleText(row.get(1).asText()) + ": " + cleanRuleText(row.get(8).asText())),
              multiSwatches);
        }
      }
    }
    var matrix = workbook.get("sheets").get(2);
    var groupSwatches = new LinkedHashMap<String, Map<String, Object>>();
    int sum = 0, count = 0;
    for (int i = 0; i < colors.size(); i++) {
      for (int j = i + 1; j < colors.size(); j++) {
        var matched = pairMatch(matrix, colors.get(i), colors.get(j));
        sum += matched.score();
        var sRow = toSwatch(matched.rowLabel());
        var sCol = toSwatch(matched.colLabel());
        groupSwatches.put(sRow.get("hex").toString(), sRow);
        groupSwatches.put(sCol.get("hex").toString(), sCol);
        count++;
      }
    }
    if (count == 0) {
      var matched = pairMatch(matrix, colors.getFirst(), colors.getFirst());
      var sw = toSwatch(matched.rowLabel());
      return new ColorEval(matched.score(), sw.get("name").toString(), List.of(sw));
    }
    var swatches = new ArrayList<>(groupSwatches.values());
    String note = String.join(" + ", swatches.stream().map(s -> s.get("name").toString()).toList());
    return new ColorEval(Math.round((float) sum / count), note, swatches);
  }

  private record PairMatch(int score, String rowLabel, String colLabel) {}

  /** Giữ tính định hướng áo/quần của matrix, không tự làm đối xứng bảng. */
  private PairMatch pairMatch(JsonNode matrix, String a, String b) {
    int row = 1, col = 1;
    long rd = Long.MAX_VALUE, cd = Long.MAX_VALUE;
    for (int i = 1; i < matrix.size(); i++) {
      long d = distance(a, hex(matrix.get(i).get(0).asText()));
      if (d < rd) {
        rd = d;
        row = i;
      }
    }
    for (int j = 1; j <= 12; j++) {
      long d = distance(b, hex(matrix.get(0).get(j).asText()));
      if (d < cd) {
        cd = d;
        col = j;
      }
    }
    return new PairMatch(
        Integer.parseInt(matrix.get(row).get(col).asText()),
        cleanRuleText(matrix.get(row).get(0).asText()),
        cleanRuleText(matrix.get(0).get(col).asText()));
  }

  /** Lấy HEX trong nhãn Excel đã đối chiếu. */
  private static String hex(String label) {
    var m = HEX_CODE.matcher(label);
    if (!m.find()) throw new IllegalStateException("Missing HEX");
    return m.group();
  }

  /** Khoảng cách RGB chỉ để xấp xỉ nhóm màu, không gọi là độ sai màu CIE. */
  private static long distance(String a, String b) {
    int x = Integer.parseInt(a.substring(1), 16), y = Integer.parseInt(b.substring(1), 16);
    long sum = 0;
    for (int s : new int[] {0, 8, 16}) {
      int d = ((x >> s) & 255) - ((y >> s) & 255);
      sum += (long) d * d;
    }
    return sum;
  }

  /** Chuyển RGB sang HSV để áp dụng điều kiện phạt saturation/hue. */
  private static float[] hsv(String hex) {
    var c = Color.decode(hex);
    return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
  }

  /** Ngưỡng phân hạng được đối chiếu sheet 1: 85/70/50. */
  public static String tier(int total) {
    return total >= 85
        ? "RECOMMENDED"
        : total >= 70 ? "ACCEPTABLE" : total >= 50 ? "NEEDS_ADJUSTMENT" : "NOT_RECOMMENDED";
  }

  private String rootGenderCategory(String categoryId) {
    String current = categoryId;
    var visited = new HashSet<String>();
    while (current != null && !current.isBlank() && visited.add(current)) {
      if ("DM000001".equals(current) || "DM000002".equals(current)) return current;
      var rows = db.rows("SELECT MaDanhMucCha FROM DanhMuc WHERE MaDanhMuc=?", current);
      if (rows.isEmpty()) break;
      current = Db.text(rows.getFirst(), "MaDanhMucCha");
    }
    return "";
  }

  /** Quét tối đa 200 SKU có tồn, đúng nhóm đối ứng và cùng giới tính; trả URL ảnh catalog chuẩn. */
  public List<Map<String, Object>> recommend(String variant, boolean tucked) {
    var g = garment(variant);
    String target =
        switch (Db.text(g, "PhanLoaiVTON")) {
          case "upper_body" -> "lower_body";
          case "lower_body" -> "upper_body";
          default -> "none";
        };
    String genderRoot = rootGenderCategory(Db.text(g, "MaDanhMuc"));
    String genderFilter = genderRoot.isEmpty()
        ? ""
        : " AND p.MaDanhMuc IN (SELECT d.MaDanhMuc FROM DanhMuc d WHERE d.MaDanhMuc=? OR d.MaDanhMucCha=?"
            + " OR d.MaDanhMucCha IN (SELECT s.MaDanhMuc FROM DanhMuc s WHERE s.MaDanhMucCha=?))";
    var sqlParams = new ArrayList<Object>();
    sqlParams.add(target);
    if (!genderRoot.isEmpty()) {
      sqlParams.add(genderRoot);
      sqlParams.add(genderRoot);
      sqlParams.add(genderRoot);
    }
    var result = new ArrayList<Map<String, Object>>();
    for (var row :
        db.rows(
            "SELECT b.MaBienThe,b.MaQuanLyHangHoa AS sku,b.KichCo AS size,b.SoLuongTonKho AS stock,"
                + "COALESCE(b.ViTriGiaKe,'KHO-01') AS shelf,b.TenMau,b.MaMauSac,"
                + "COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS price,p.MaSanPham,p.TenSanPham,"
                + " (SELECT MaHinhAnh FROM HinhAnhSanPham WHERE MaSanPham=p.MaSanPham ORDER BY LaAnhDaiDien DESC,ThuTuHienThi,MaHinhAnh LIMIT 1) AS MaHinhAnh"
                + " FROM BienTheSanPham b JOIN SanPham p ON p.MaSanPham=b.MaSanPham"
                + " WHERE b.SoLuongTonKho>0 AND b.TrangThai='DANG_BAN' AND p.TrangThaiSanPham='DANG_KINH_DOANH'"
                + " AND p.PhanLoaiVTON=?" + genderFilter + " ORDER BY b.MaBienThe LIMIT 200",
            sqlParams.toArray())) {
      var score = evaluate(variant, Db.text(row, "MaBienThe"), tucked);
      int proj = (int) score.get("total");
      if (proj >= 85) {
        String imageId = Db.text(row, "MaHinhAnh");
        String imageUrl = imageId.isEmpty() ? null : "/api/v1/catalog/images/" + imageId + "/content?kind=original";
        String reason = Db.text(score, "stylistComment");
        if (reason.length() > 250) reason = reason.substring(0, 247).trim() + "...";
        var map = new LinkedHashMap<String, Object>();
        map.put("variantId", row.get("MaBienThe"));
        map.put("productId", row.get("MaSanPham"));
        map.put("sku", row.get("sku"));
        map.put("size", row.get("size"));
        map.put("stock", row.get("stock"));
        map.put("shelf", row.get("shelf"));
        map.put("name", row.get("TenSanPham"));
        map.put("productName", row.get("TenSanPham"));
        map.put("colorName", row.get("TenMau"));
        map.put("colorHex", row.get("MaMauSac"));
        map.put("price", row.get("price"));
        map.put("imageUrl", imageUrl);
        map.put("projectedScore", proj);
        map.put("reason", reason);
        map.put("assessmentType", "CATALOG_RULE_ESTIMATE");
        result.add(map);
      }
    }
    result.sort(Comparator.comparingInt(x -> -(int) x.get("projectedScore")));
    return result.stream().limit(10).toList();
  }
}
