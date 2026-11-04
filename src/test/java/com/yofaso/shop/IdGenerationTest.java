package com.yofaso.shop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.yofaso.shop.common.Db;

@SpringBootTest
class IdGenerationTest {
  @Autowired Db db;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) throws Exception {
    var registry = Files.createTempDirectory(Path.of("target"), "test-runtime-");
    r.add("app.runtime-root", registry::toString);
    r.add("app.storage-root", () -> "./data/test-media/" + UUID.randomUUID());
  }

  @Test
  void testPrefixMapCoverage() {
    // 17 bảng có khóa chính đơn trong ERD
    assertEquals(17, Db.PREFIX_MAP.size());
    assertTrue(Db.PREFIX_MAP.containsKey("DC"));
    assertTrue(Db.PREFIX_MAP.containsKey("ND"));
    assertTrue(Db.PREFIX_MAP.containsKey("DH"));
    assertTrue(Db.PREFIX_MAP.containsKey("SP"));
    assertTrue(Db.PREFIX_MAP.containsKey("BT"));
  }

  @Test
  void testNextIdSequentialFormat() {
    String id1 = db.nextId("DC");
    String id2 = db.nextId("DC");
    
    assertNotNull(id1);
    assertNotNull(id2);
    assertTrue(id1.matches("^DC\\d{8}$"), "Mã sổ địa chỉ phải có dạng DC + 8 chữ số: " + id1);
    assertTrue(id2.matches("^DC\\d{8}$"), "Mã sổ địa chỉ phải có dạng DC + 8 chữ số: " + id2);
    
    long num1 = Long.parseLong(id1.substring(2));
    long num2 = Long.parseLong(id2.substring(2));
    assertEquals(num1 + 1, num2, "Hai mã sinh liên tiếp phải tăng dần đúng 1 đơn vị");
  }

  @Test
  void testOrderAndInventoryPrefixFormats() {
    String dh = db.nextId("DH");
    assertTrue(dh.matches("^DH\\d{8}$"), "Mã đơn hàng phải có dạng DH + 8 chữ số: " + dh);

    String dm = db.nextId("DM");
    assertTrue(dm.matches("^DM\\d{6}$"), "Mã danh mục phải có dạng DM + 6 chữ số: " + dm);

    String km = db.nextId("KM");
    assertTrue(km.matches("^KM\\d{6}$"), "Mã khuyến mãi phải có dạng KM + 6 chữ số: " + km);
  }
}