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
    int proportion = ruleScore(1, prop, 5);
    List<String> colors =
        new ArrayList<>(Arrays.asList(Db.text(a, "MaMauSac").toUpperCase(Locale.ROOT).split(",")));
    if (b != null)
      colors.addAll(Arrays.asList(Db.text(b, "MaMauSac").toUpperCase(Locale.ROOT).split(",")));
    int color = colorScore(colors);
    String pa = Db.text(a, "LoaiHoaTiet"), pb = b == null ? "VAI_TRON" : Db.text(b, "LoaiHoaTiet");
    String patternRule = pattern(pa, pb);
    int pattern = ruleScore(4, patternRule, 3);
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
    int penalty = penalties.stream().mapToInt(p -> Math.abs(ruleScore(5, p, 2))).sum();
    int total =
        Math.max(
            0,
            Math.min(100, (int) Math.round(.4 * proportion + .4 * color + .2 * pattern - penalty)));
    return Map.ofEntries(
        Map.entry("total", total),
        Map.entry("proportion", proportion),
        Map.entry("color", color),
        Map.entry("pattern", pattern),
        Map.entry("penalty", penalty),
        Map.entry("tier", tier(total)),
        Map.entry("proportionRule", prop),
        Map.entry("patternRule", patternRule),
        Map.entry("penaltyRules", penalties),
        Map.entry("source", "ScoringTable.xlsx; catalog rules v1"),
        Map.entry("assessmentType", "CATALOG_RULE_ESTIMATE"),
        Map.entry(
            "assumptions",
            List.of(
                "Sơ vin do người dùng khai báo, không suy ra từ ảnh.",
                "Màu gần nhất trong bảng RGB; màu ngoài bảng được xấp xỉ, không coi là đo màu"
                    + " chuẩn.",
                "Chưa chấm phụ kiện, chất liệu gân, họa tiết chìm do catalog chưa có field tương"
                    + " ứng.")),
        Map.entry(
            "advice",
            total >= 85
                ? "Bản phối có tỉ lệ và màu sắc hài hòa theo quy tắc catalog."
                : "Bạn có thể thử đổi phom hoặc màu của một món để bản phối cân đối hơn."));
  }

  /** Chọn một trong 12 quy tắc tỷ lệ; fallback casual được công khai như ước lượng. */
  private String proportion(Map<String, Object> a, Map<String, Object> b, boolean tucked) {
    if (b == null) {
      Problem.require(
          "CAP_CAO".equals(a.get("LoaiCapQuan")),
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

  /** Tra điểm trực tiếp theo mã dòng Excel để tránh rải hằng số trong code. */
  private int ruleScore(int sheet, String code, int column) {
    for (var row : workbook.get("sheets").get(sheet))
      if (row.get(0).asText().equals(code)) return Integer.parseInt(row.get(column).asText());
    throw new IllegalStateException("Rule missing: " + code);
  }

  /** Màu một món không có cặp đối chiếu được tính như cùng màu trong matrix. */
  private int colorScore(List<String> colors) {
    var unique = colors.stream().distinct().sorted().toList();
    if (unique.size() >= 3) {
      for (var row : workbook.get("sheets").get(3)) {
        if (!row.get(0).asText().startsWith("MULTI_")) continue;
        var set = new TreeSet<String>();
        for (int i = 2; i <= 5; i++) {
          var m = java.util.regex.Pattern.compile("#[A-Fa-f0-9]{6}").matcher(row.get(i).asText());
          if (m.find()) set.add(m.group().toUpperCase(Locale.ROOT));
        }
        if (set.equals(new TreeSet<>(unique))) return Integer.parseInt(row.get(7).asText());
      }
    }
    int sum = 0, count = 0;
    for (int i = 0; i < colors.size(); i++)
      for (int j = i + 1; j < colors.size(); j++) {
        sum += pair(colors.get(i), colors.get(j));
        count++;
      }
    return count == 0
        ? pair(colors.getFirst(), colors.getFirst())
        : Math.round((float) sum / count);
  }

  /** Giữ tính định hướng áo/quần của matrix, không tự làm đối xứng bảng. */
  private int pair(String a, String b) {
    var matrix = workbook.get("sheets").get(2);
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
    return Integer.parseInt(matrix.get(row).get(col).asText());
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

  /** Quét tối đa 200 SKU có tồn và đúng nhóm đối ứng, không bịa sản phẩm hoặc ép đủ 90. */
  public List<Map<String, Object>> recommend(String variant, boolean tucked) {
    var g = garment(variant);
    String target =
        switch (Db.text(g, "PhanLoaiVTON")) {
          case "upper_body" -> "lower_body";
          case "lower_body" -> "upper_body";
          default -> "none";
        };
    var result = new ArrayList<Map<String, Object>>();
    for (var row :
        db.rows(
            "SELECT b.MaBienThe,p.TenSanPham FROM BienTheSanPham b JOIN SanPham p ON"
                + " p.MaSanPham=b.MaSanPham WHERE b.SoLuongTonKho>0 AND b.TrangThai='DANG_BAN' AND"
                + " p.TrangThaiSanPham='DANG_KINH_DOANH' AND p.PhanLoaiVTON=? ORDER BY b.MaBienThe"
                + " LIMIT 200",
            target)) {
      var score = evaluate(variant, Db.text(row, "MaBienThe"), tucked);
      if ((int) score.get("total") >= 90)
        result.add(
            Map.of(
                "variantId",
                row.get("MaBienThe"),
                "name",
                row.get("TenSanPham"),
                "projectedScore",
                score.get("total"),
                "assessmentType",
                "CATALOG_RULE_ESTIMATE"));
    }
    result.sort(Comparator.comparingInt(x -> -(int) x.get("projectedScore")));
    return result.stream().limit(10).toList();
  }
}
