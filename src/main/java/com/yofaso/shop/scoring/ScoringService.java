package com.yofaso.shop.scoring;

import com.yofaso.shop.common.*;
import java.awt.Color;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Điểm quy tắc 40/40/20 từ catalog; không phải phép đo vóc dáng thật từ ảnh render. */
@Service
public class ScoringService {
  private final Db db;
  private final JsonNode workbook;

  /** Nạp bản trích nguyên workbook có nguồn, không sửa Excel gốc. */
  public ScoringService(Db db, JsonMapper mapper) throws java.io.IOException {
    this.db = db;
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
        "SELECT p.*,b.MaBienThe,b.MaMauSac FROM SanPham p JOIN BienTheSanPham b ON"
            + " b.MaSanPham=p.MaSanPham WHERE b.MaBienThe=? AND"
            + " p.TrangThaiSanPham='DANG_KINH_DOANH' AND b.TrangThai<>'NGUNG_BAN'",
        variant);
  }

  /** Chấm một set áo/quần hoặc đầm, thông báo rõ giả định sơ vin từ người dùng. */
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
    List<String> colors =
        new ArrayList<>(Arrays.asList(Db.text(a, "MaMauSac").toUpperCase(Locale.ROOT).split(",")));
    if (b != null)
      colors.addAll(Arrays.asList(Db.text(b, "MaMauSac").toUpperCase(Locale.ROOT).split(",")));
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
    String tierAction = cleanRuleText(tierNode.get(5).asText());
    String formula = String.format("S = 0.4·%d + 0.4·%d + 0.2·%d - %d", proportion, color, pattern, penalty);
    String propNote = cleanRuleText(propRow.get(6).asText());
    String colNote = colorEval.note();
    String patNote = cleanRuleText(patRow.get(4).asText());
    String penaltyNote = penaltyNotes.isEmpty() ? "" : String.join(" ", penaltyNotes);
    String stylistComment = tierMeaning + " " + propNote + " " + tierAction;

    var breakdown = Map.of(
        "proportion", Map.of("score", proportion, "weight", "40%", "ruleCode", prop, "ruleName", cleanRuleText(propRow.get(1).asText()), "stylistNote", propNote),
        "color", Map.of("score", color, "weight", "40%", "stylistNote", colNote),
        "pattern", Map.of("score", pattern, "weight", "20%", "ruleCode", patternRule, "stylistNote", patNote),
        "penalty", Map.of("score", penalty, "rules", penalties, "stylistNote", penaltyNote)
    );

    return Map.ofEntries(
        Map.entry("total", total),
        Map.entry("proportion", proportion),
        Map.entry("color", color),
        Map.entry("pattern", pattern),
        Map.entry("penalty", penalty),
        Map.entry("tier", tier),
        Map.entry("tierLabel", tierLabel),
        Map.entry("formula", formula),
        Map.entry("breakdown", breakdown),
        Map.entry("stylistComment", stylistComment),
        Map.entry("aiModelSource", "WORKBOOK_RULES_JSON"),
        Map.entry("proportionRule", prop),
        Map.entry("patternRule", patternRule),
        Map.entry("penaltyRules", penalties),
        Map.entry("source", workbook.path("source").asText("ScoringTable.xlsx")),
        Map.entry("assessmentType", "CATALOG_RULE_ESTIMATE"),
        Map.entry(
            "assumptions",
            List.of(
                "Sơ vin do người dùng khai báo, không suy ra từ ảnh.",
                "Màu gần nhất trong bảng RGB; màu ngoài bảng được xấp xỉ, không coi là đo màu"
                    + " chuẩn.",
                "Chưa chấm phụ kiện, chất liệu gân, họa tiết chìm do catalog chưa có field tương"
                    + " ứng.")),
        Map.entry("advice", stylistComment));
  }

  /** Thông tin đơn lẻ từ workbook-rules.json khi khách mới thử 1 món upper/lower chưa ghép set. */
  public Map<String, Object> singleItemPreview(String variantId) {
    var g = garment(variantId);
    List<String> colors = Arrays.asList(Db.text(g, "MaMauSac").toUpperCase(Locale.ROOT).split(","));
    var colorEval = evaluateColors(colors);
    String pa = Db.text(g, "LoaiHoaTiet");
    String patternRule = pattern(pa, "VAI_TRON");
    var patRow = ruleRow(4, patternRule);
    return Map.of(
        "evaluated", false,
        "singleItem", true,
        "colorName", Db.text(g, "TenMau"),
        "colorNote", colorEval.note(),
        "patternRule", patternRule,
        "patternNote", cleanRuleText(patRow.get(4).asText()),
        "source", workbook.path("source").asText("ScoringTable.xlsx"));
  }

  /** Làm giàu thông tin hiển thị từ workbook-rules.json cho phiên đã lưu điểm. */
  public Map<String, Object> enrichSavedSession(String tier, String propRule) {
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

  private record ColorEval(int score, String note) {}

  private static String cleanRuleText(String text) {
    return text == null ? "" : text.replaceAll("\\s*\\n\\s*", " ").replaceAll("\\s{2,}", " ").trim();
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

  /** Màu một món không có cặp đối chiếu được tính như cùng màu trong matrix; đọc nhãn từ JSON. */
  private ColorEval evaluateColors(List<String> colors) {
    var unique = colors.stream().distinct().sorted().toList();
    if (unique.size() >= 3) {
      for (var row : workbook.get("sheets").get(3)) {
        if (!row.get(0).asText().startsWith("MULTI_")) continue;
        var set = new TreeSet<String>();
        for (int i = 2; i <= 5; i++) {
          var m = java.util.regex.Pattern.compile("#[A-Fa-f0-9]{6}").matcher(row.get(i).asText());
          if (m.find()) set.add(m.group().toUpperCase(Locale.ROOT));
        }
        if (set.equals(new TreeSet<>(unique))) {
          return new ColorEval(
              Integer.parseInt(row.get(7).asText()),
              cleanRuleText(row.get(1).asText()) + ": " + cleanRuleText(row.get(8).asText()));
        }
      }
    }
    var matrix = workbook.get("sheets").get(2);
    var groupLabels = new LinkedHashSet<String>();
    int sum = 0, count = 0;
    for (int i = 0; i < colors.size(); i++) {
      for (int j = i + 1; j < colors.size(); j++) {
        var matched = pairMatch(matrix, colors.get(i), colors.get(j));
        sum += matched.score();
        groupLabels.add(matched.rowLabel());
        groupLabels.add(matched.colLabel());
        count++;
      }
    }
    if (count == 0) {
      var matched = pairMatch(matrix, colors.getFirst(), colors.getFirst());
      return new ColorEval(matched.score(), matched.rowLabel());
    }
    return new ColorEval(Math.round((float) sum / count), String.join(" + ", groupLabels));
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
    var m = java.util.regex.Pattern.compile("#[A-Fa-f0-9]{6}").matcher(label);
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
            "SELECT b.MaBienThe,b.TenMau,COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS price,p.MaSanPham,p.TenSanPham,"
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
        var map = new LinkedHashMap<String, Object>();
        map.put("variantId", row.get("MaBienThe"));
        map.put("productId", row.get("MaSanPham"));
        map.put("name", row.get("TenSanPham"));
        map.put("productName", row.get("TenSanPham"));
        map.put("colorName", row.get("TenMau"));
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
