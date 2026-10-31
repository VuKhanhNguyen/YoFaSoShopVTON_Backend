package com.yofaso.shop.location;

import com.yofaso.shop.common.Problem;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Catalog địa chỉ immutable trong RAM, đọc classpath một lần; không gọi API bên ngoài. */
@Service
public class LocationService {
  public record Unit(String code, String name, String type, String provinceCode, String districtCode) {
    public String fullName() { return type + " " + name; }
  }
  public record AddressText(String city, String ward) {}
  private final Map<String, Unit> provinces, wards, oldProvinces, districts, oldWards;
  private final Map<String, List<Map<String, Object>>> history;
  private final Map<String, List<String>> provinceHistory;
  private final Map<String, Object> metadata;

  /** Kiểm tra mã duy nhất/quan hệ khi nạp để dữ liệu lỗi không âm thầm đi vào sổ địa chỉ. */
  public LocationService(JsonMapper json) throws IOException {
    var p = read(json, "provinces");
    metadata = Map.copyOf(map(p.get("metadata")));
    provinces = units(p);
    wards = units(read(json, "wards"));
    oldProvinces = units(read(json, "provinces-legacy"));
    districts = units(read(json, "districts-legacy"));
    oldWards = units(read(json, "wards-legacy"));
    for (var w : wards.values()) check(provinces.containsKey(w.provinceCode()), "Tỉnh của phường mới không tồn tại");
    for (var d : districts.values()) check(oldProvinces.containsKey(d.provinceCode()), "Tỉnh của huyện cũ không tồn tại");
    for (var w : oldWards.values()) {
      var d = districts.get(w.districtCode());
      check(d != null && d.provinceCode().equals(w.provinceCode()), "Quan hệ xã/huyện cũ không hợp lệ");
    }
    var h = new HashMap<String, List<Map<String, Object>>>();
    for (var row : rows(read(json, "ward-merge-history"))) {
      String oldCode = text(row, "old_ward_code"), newCode = text(row, "new_ward_code");
      var target = wards.get(newCode);
      check(target != null && target.provinceCode().equals(text(row, "new_province_code")), "Đích chuyển đổi không hợp lệ");
      // History có thể bao gồm xã cũ ngoài snapshot legacy; giữ nguyên provenance.
      String key = oldCode.isBlank() ? "district:" + text(row, "old_district_code") : oldCode;
      h.computeIfAbsent(key, k -> new ArrayList<>()).add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
    }
    h.replaceAll((k, v) -> List.copyOf(v));
    history = Map.copyOf(h);
    var ph = new HashMap<String, List<String>>();
    for (var row : rows(p)) {
      for (var old : text(row, "merged_from").split(";"))
        ph.computeIfAbsent(old, k -> new ArrayList<>()).add(text(row, "code"));
    }
    ph.replaceAll((k, v) -> List.copyOf(v));
    provinceHistory = Map.copyOf(ph);
  }

  /** File nguồn kèm metadata/license vẫn được giữ nguyên, không tải lại mỗi request. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(JsonMapper json, String suffix) throws IOException {
    try (var input = new ClassPathResource("location/tinhthanhpho-" + suffix + ".json").getInputStream()) {
      return json.readValue(input, Map.class);
    }
  }
  @SuppressWarnings("unchecked") private static Map<String, Object> map(Object v) { return (Map<String, Object>) v; }
  @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> file) { return (List<Map<String, Object>>) file.get("data"); }
  private static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
  private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
  private static Map<String, Unit> units(Map<String, Object> file) {
    var result = new LinkedHashMap<String, Unit>();
    for (var row : rows(file)) {
      var unit = new Unit(text(row, "code"), text(row, "name"), text(row, "type"), text(row, "province_code"), text(row, "district_code"));
      check(!unit.code().isBlank() && result.putIfAbsent(unit.code(), unit) == null, "Mã địa chỉ trùng/rỗng");
    }
    return Collections.unmodifiableMap(result);
  }
  public Map<String, Object> profile() {
    return Map.of("metadata", metadata, "newProvinceCount", provinces.size(), "newWardCount", wards.size(),
        "legacyProvinceCount", oldProvinces.size(), "legacyDistrictCount", districts.size(),
        "legacyWardCount", oldWards.size(), "storage", "classpath-json-in-memory", "externalCallsPerRequest", 0);
  }
  public List<Unit> provinces(boolean legacy) { return List.copyOf((legacy ? oldProvinces : provinces).values()); }
  public Unit province(String code, boolean legacy) { return require(legacy ? oldProvinces : provinces, code); }
  public Unit district(String code) { return require(districts, code); }
  public Unit ward(String code, boolean legacy) { return require(legacy ? oldWards : wards, code); }
  public List<Unit> districts(String province) {
    province(province, true);
    return districts.values().stream().filter(d -> d.provinceCode().equals(province)).toList();
  }
  public List<Unit> wards(String parent, boolean legacy) {
    if (legacy) district(parent); else province(parent, false);
    return (legacy ? oldWards : wards).values().stream()
        .filter(w -> (legacy ? w.districtCode() : w.provinceCode()).equals(parent)).toList();
  }
  private static Unit require(Map<String, Unit> source, String code) {
    Unit u = source.get(code);
    Problem.require(u != null, 404, "LOCATION_NOT_FOUND", "Không tìm thấy mã đơn vị hành chính.");
    return u;
  }
  /** Ghép địa chỉ để preview; dữ liệu lưu DB vẫn chỉ street/ward/city của ERD. */
  public Map<String, Object> full(String province, String district, String ward, String street, boolean legacy) {
    var p = province(province, legacy);
    var d = legacy ? district(district) : null;
    boolean districtOnly = legacy && (ward == null || ward.isBlank());
    if (districtOnly) Problem.require(wards(district, true).isEmpty(), 422, "LOCATION_WARD_REQUIRED", "Huyện này có xã/phường; cần chọn wardCode.");
    var w = districtOnly ? null : ward(ward, legacy);
    Problem.require((w == null || w.provinceCode().equals(p.code())) && (d == null || d.provinceCode().equals(p.code())),
        422, "LOCATION_PARENT_MISMATCH", "Đơn vị không thuộc tỉnh đã chọn.");
    var names = new ArrayList<String>();
    if (street != null && !street.isBlank()) names.add(street.strip());
    if (w != null) names.add(w.fullName());
    if (legacy) {
      Problem.require(w == null || w.districtCode().equals(d.code()),
          422, "LOCATION_PARENT_MISMATCH", "Phường/xã không thuộc quận/huyện đã chọn.");
      names.add(d.fullName());
    }
    names.add(p.fullName());
    var result = new LinkedHashMap<String, Object>();
    result.put("province", p);
    result.put("ward", w);
    if (legacy) result.put("district", d);
    result.put("street", Objects.toString(street, "").strip());
    result.put("fullAddress", String.join(", ", names));
    return result;
  }
  /** Tìm không dấu có phân trang, không trả toàn bộ catalog lớn theo mỗi ký tự gõ. */
  public Map<String, Object> search(String q, String province, int limit, int offset, boolean legacy) {
    String needle = normalize(q);
    Problem.require(!needle.isBlank() && q.length() <= 100 && limit >= 1 && limit <= 100 && offset >= 0 && offset <= 20000,
        400, "LOCATION_QUERY_INVALID", "q cần 1..100 ký tự; limit 1..100, offset 0..20000.");
    if (province != null) province(province, legacy);
    var found = (legacy ? oldWards : wards).values().stream().filter(w ->
        (province == null || province.equals(w.provinceCode())) &&
        (w.code().equals(q) || normalize(w.fullName()).contains(needle))).toList();
    return Map.of("items", found.stream().skip(offset).limit(limit).toList(), "total", found.size(), "limit", limit, "offset", offset);
  }
  public List<Map<String, Object>> wardHistory(String oldCode) {
    var rows = history.get(oldCode);
    Problem.require(rows != null || oldWards.containsKey(oldCode), 404, "LOCATION_NOT_FOUND", "Không tìm thấy mã xã cũ.");
    return rows == null ? List.of() : rows;
  }
  public List<Unit> provinceHistory(String oldCode) {
    province(oldCode, true);
    return provinceHistory.getOrDefault(oldCode, List.of()).stream().map(provinces::get).toList();
  }
  public List<Map<String, Object>> districtHistory(String code) {
    district(code);
    return history.getOrDefault("district:" + code, List.of());
  }
  /** Không suy đoán khi một xã cũ bị chia: luôn trả candidates và required confirmation. */
  public Map<String, Object> convert(String oldProvince, String oldDistrict, String oldWard, String street) {
    var old = full(oldProvince, oldDistrict, oldWard, street, true);
    var records = (oldWard == null || oldWard.isBlank() ? districtHistory(oldDistrict) : wardHistory(oldWard)).stream().filter(r -> oldProvince.equals(text(r, "old_province_code"))
        && oldDistrict.equals(text(r, "old_district_code"))).toList();
    var candidates = records.stream().map(r -> text(r, "new_ward_code")).distinct().sorted()
        .map(code -> full(wards.get(code).provinceCode(), null, code, street, false)).toList();
    boolean exact = candidates.size() == 1 && records.stream().allMatch(r -> "full".equals(r.get("scope")));
    return Map.of("oldAddress", old, "status", candidates.isEmpty() ? "UNMAPPED" : exact ? "EXACT" : "NEEDS_CONFIRMATION",
        "requiresConfirmation", !exact, "candidates", candidates, "history", records, "dataVersion", metadata.get("data_version"));
  }
  /** Code là dữ liệu dropdown; tên text cũ vẫn được chấp nhận khi resolve duy nhất. */
  public AddressText validateAddress(String cityCode, String wardCode, String city, String ward) {
    Unit p;
    Unit w;
    if (cityCode != null || wardCode != null) {
      Problem.require(cityCode != null && wardCode != null, 422, "LOCATION_CODES_REQUIRED", "Cần gửi đủ provinceCode và wardCode.");
      p = province(cityCode, false);
      w = ward(wardCode, false);
      Problem.require(w.provinceCode().equals(p.code()), 422, "LOCATION_PARENT_MISMATCH", "Phường/xã không thuộc tỉnh đã chọn.");
      Problem.require(matches(p, city) && matches(w, ward), 422, "LOCATION_TEXT_MISMATCH", "Tên địa chỉ không khớp mã dropdown đã chọn.");
    } else {
      var ps = provinces.values().stream().filter(u -> matches(u, city)).toList();
      Problem.require(ps.size() == 1, 422, "LOCATION_INVALID", "Chọn tỉnh/thành hiện hành từ dropdown.");
      p = ps.getFirst();
      var ws = wards.values().stream().filter(u -> u.provinceCode().equals(p.code()) && matches(u, ward)).toList();
      Problem.require(ws.size() == 1, 422, "LOCATION_AMBIGUOUS", "Chọn phường/xã hiện hành bằng mã dropdown.");
      w = ws.getFirst();
    }
    return new AddressText(p.fullName(), w.fullName());
  }
  private static boolean matches(Unit unit, String name) {
    return name != null && (normalize(unit.name()).equals(normalize(name)) || normalize(unit.fullName()).equals(normalize(name)));
  }
  private static String normalize(String value) {
    return Normalizer.normalize(value == null ? "" : value.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").replace('đ', 'd').replaceAll("\\s+", " ");
  }
}
