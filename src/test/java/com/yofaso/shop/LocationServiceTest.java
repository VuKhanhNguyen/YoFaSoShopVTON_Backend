package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.location.LocationService;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Dataset/convert/ownership input không cần database; chưa thay thế test HTTP/MySQL. */
class LocationServiceTest {
  private LocationService load() throws Exception { return new LocationService(JsonMapper.builder().build()); }

  @Test void currentAndLegacyCatalogsAreSeparate() throws Exception {
    var service = load();
    assertEquals(34, service.provinces(false).size());
    assertEquals(63, service.provinces(true).size());
    assertEquals(94, service.wards("48", false).size());
    assertEquals("01", service.provinces(false).getFirst().code());
    assertEquals("Hải Châu", service.ward("20242", false).name());
  }

  @Test void addressCodesCannotSpoofProvinceOrText() throws Exception {
    var service = load();
    var result = service.validateAddress("48", "20242", "Đà Nẵng", "Phường Hải Châu");
    assertEquals("Thành phố Đà Nẵng", result.city());
    assertEquals("Phường Hải Châu", result.ward());
    assertThrows(Problem.class, () -> service.validateAddress("01", "20242", "Hà Nội", "Hải Châu"));
    assertThrows(Problem.class, () -> service.validateAddress("48", "20242", "Đà Nẵng", "Xã giả"));
    assertThrows(Problem.class, () -> service.validateAddress("48", null, "Đà Nẵng", "Hải Châu"));
  }

  @Test void splitWardNeverChoosesOneTargetSilently() throws Exception {
    var service = load();
    boolean splitExercised = false;
    for (var p : service.provinces(true)) {
      for (var d : service.districts(p.code())) {
        for (var w : service.wards(d.code(), true)) {
          var history = service.wardHistory(w.code());
          if (history.stream().anyMatch(r -> "partial".equals(r.get("scope")))) {
            var conversion = service.convert(p.code(), d.code(), w.code(), "12 Nguyễn Văn Linh");
            assertEquals("NEEDS_CONFIRMATION", conversion.get("status"));
            assertEquals(true, conversion.get("requiresConfirmation"));
            assertFalse(((List<?>) conversion.get("candidates")).isEmpty());
            splitExercised = true;
            break;
          }
        }
        if (splitExercised) break;
      }
      if (splitExercised) break;
    }
    assertTrue(splitExercised, "Fixture phải có xã bị chia từ history nguồn");
  }

  @Test void searchIsBoundedAndSupportsUnaccentedInput() throws Exception {
    var service = load();
    var result = service.search("hai chau", "48", 10, 0, false);
    assertFalse(((List<?>) result.get("items")).isEmpty());
    assertThrows(Problem.class, () -> service.search("a", null, 1000000, 0, false));
    assertThrows(Problem.class, () -> service.search(" ", null, 10, 0, false));
  }

  @Test void legacyIslandDistrictWithoutWardsCanConvert() throws Exception {
    var service = load();
    assertTrue(service.wards("318", true).isEmpty());
    var conversion = service.convert("31", "318", null, "12 Đường Test");
    assertFalse(((List<?>) conversion.get("candidates")).isEmpty());
    assertThrows(Problem.class, () -> service.convert("01", "001", null, "12 Đường Test"));
  }
}
