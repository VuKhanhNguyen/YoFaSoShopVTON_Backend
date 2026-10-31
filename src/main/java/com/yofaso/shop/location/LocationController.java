package com.yofaso.shop.location;

import com.yofaso.shop.common.Problem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Dropdown địa chỉ cũ/mới và chuyển đổi; dữ liệu nạp sẵn nên không có quota nhà cung cấp. */
@RestController
@RequestMapping("/api/v1")
@Validated
public class LocationController {
  public record Conversion(
      @NotBlank @Pattern(regexp="[0-9]{2}") String provinceCode,
      @NotBlank @Pattern(regexp="[0-9]{3}") String districtCode,
      @Pattern(regexp="[0-9]{5}") String wardCode,
      @Size(max=255) String street) {}
  public record Batch(@NotEmpty @Size(max=100) List<@NotNull @Valid Conversion> addresses) {}
  private final LocationService locations;
  public LocationController(LocationService locations) { this.locations = locations; }

  /** 63 tỉnh/thành của snapshot cũ. */
  @GetMapping("/provinces") public List<LocationService.Unit> provinces() { return locations.provinces(true); }
  @GetMapping("/provinces/{code}") public LocationService.Unit province(@PathVariable String code) { return locations.province(code, true); }
  /** Dropdown huyện theo tỉnh cũ. */
  @GetMapping("/provinces/{code}/districts") public List<LocationService.Unit> districts(@PathVariable String code) { return locations.districts(code); }
  @GetMapping("/districts/{code}") public LocationService.Unit district(@PathVariable String code) { return locations.district(code); }
  /** Dropdown xã cũ theo huyện cũ. */
  @GetMapping("/districts/{code}/wards") public List<LocationService.Unit> oldWards(@PathVariable String code) { return locations.wards(code, true); }
  @GetMapping("/wards/{code}") public LocationService.Unit oldWard(@PathVariable String code) { return locations.ward(code, true); }
  /** Địa chỉ hiện hành chỉ có tỉnh -> phường/xã/đặc khu. */
  @GetMapping("/new-provinces") public List<LocationService.Unit> newProvinces() { return locations.provinces(false); }
  @GetMapping("/new-provinces/{code}") public LocationService.Unit newProvince(@PathVariable String code) { return locations.province(code, false); }
  @GetMapping("/new-provinces/{code}/wards") public List<LocationService.Unit> newWards(@PathVariable String code) { return locations.wards(code, false); }
  @GetMapping("/new-wards/{code}") public LocationService.Unit newWard(@PathVariable String code) { return locations.ward(code, false); }
  /** Preview text đầy đủ trước khi gửi sổ địa chỉ. */
  @GetMapping("/full-address") public Map<String, Object> full(@RequestParam String provinceCode, @RequestParam String districtCode,
      @RequestParam(required=false) String wardCode, @RequestParam(defaultValue="") @Size(max=255) String street) {
    return locations.full(provinceCode, districtCode, wardCode, street, true);
  }
  @GetMapping("/new-full-address") public Map<String, Object> newFull(@RequestParam String provinceCode, @RequestParam String wardCode,
      @RequestParam(defaultValue="") @Size(max=255) String street) {
    return locations.full(provinceCode, null, wardCode, street, false);
  }
  /** Search có giới hạn, hỗ trợ không dấu. */
  @GetMapping("/search-address") public Map<String, Object> search(@RequestParam @Size(min=1,max=100) String q,
      @RequestParam(required=false) String provinceCode, @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
    return locations.search(q, provinceCode, limit, offset, true);
  }
  @GetMapping({"/search-new-address", "/suggest"}) public Map<String, Object> searchNew(@RequestParam @Size(min=1,max=100) String q,
      @RequestParam(required=false) String provinceCode, @RequestParam(defaultValue="20") int limit, @RequestParam(defaultValue="0") int offset) {
    return locations.search(q, provinceCode, limit, offset, false);
  }
  /** Một địa chỉ cũ có thể ra nhiều đích; client xác nhận trước khi lưu. */
  @PostMapping("/convert/address") public Map<String, Object> convert(@Valid @RequestBody Conversion body) { return convertOne(body); }
  @PostMapping("/convert/batch") public List<Map<String, Object>> batch(@Valid @RequestBody Batch body) {
    return body.addresses().stream().map(this::convertOne).toList();
  }
  private Map<String, Object> convertOne(Conversion body) {
    return locations.convert(body.provinceCode(), body.districtCode(), body.wardCode(), body.street());
  }
  /** Nguồn, license, phiên bản và số dòng; không phải hồ sơ người dùng. */
  @GetMapping("/profile") public Map<String, Object> profile() { return locations.profile(); }
  @GetMapping("/merge-history/province/{code}") public List<LocationService.Unit> provinceHistory(@PathVariable String code) { return locations.provinceHistory(code); }
  @GetMapping("/merge-history/ward/{code}") public List<Map<String, Object>> wardHistory(@PathVariable String code) { return locations.wardHistory(code); }
  @GetMapping("/merge-history/district/{code}") public List<Map<String, Object>> districtHistory(@PathVariable String code) { return locations.districtHistory(code); }
}
