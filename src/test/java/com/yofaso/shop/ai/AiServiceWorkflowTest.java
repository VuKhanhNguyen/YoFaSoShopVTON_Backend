package com.yofaso.shop.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Idempotency;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.common.RuntimeState;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AiServiceWorkflowTest {

  private static byte[] samplePng() throws Exception {
    var img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  @Test
  void replacePersonReusesSameSessionWithRevisionedAiSessionId(@TempDir Path temp) throws Exception {
    var mapper = JsonMapper.builder().build();
    try (var runtime = new RuntimeState(temp.resolve("runtime").toString(), mapper)) {
      List<String> executedSql = new ArrayList<>();
      Db db =
          new Db(null, runtime) {
            @Override
            public Map<String, Object> one(String sql, Object... args) {
              if (sql.contains("FROM NguoiDung")) return Map.of("MaNguoiDung", args[0]);
              return new LinkedHashMap<>(
                  Map.of(
                      "sessionId", "TD00000105",
                      "variantId", "BT00000001",
                      "productId", "SP00000001",
                      "categoryId", "DM000004",
                      "productName", "Áo sơ mi",
                      "vtonCategory", "upper_body",
                      "status", "CHO_XU_LY",
                      "personAssetId", "newperson"));
            }

            @Override
            public List<Map<String, Object>> rows(String sql, Object... args) {
              if (sql.contains("FROM DanhMuc") && "DM000004".equals(args[0])) {
                return List.of(Map.of("MaDanhMucCha", "DM000002"));
              }
              return List.of();
            }

            @Override
            public int exec(String sql, Object... args) {
              executedSql.add(sql);
              return 1;
            }
          };

      var media = new MediaService(temp.resolve("storage").toString(), db, null, null, "");
      String newPerson = media.save("ND00000027", "person", samplePng(), false).get("assetId").toString();

      runtime.put(
          "operations",
          "TD00000105",
          RuntimeState.row(
              "OperationId", "TD00000105",
              "OwnerId", "ND00000027",
              "KindName", "TRY_ON",
              "ResourceId", "TD00000105",
              "Payload", mapper.writeValueAsString(Map.of("sessionId", "TD00000105", "personAssetId", "oldperson")),
              "StateName", "FAILED",
              "JobId", "job-old",
              "Attempts", 1,
              "LastError", "PERSON_VALIDATION_FAILED",
              "NextAttemptAt", 999L));

      var aiService = new AiService(db, null, media, mapper, new Idempotency(db, mapper), false);
      var updated =
          aiService.replacePerson(
              "TD00000105", "ND00000027", new AiController.ReplacePerson(newPerson, null, true));

      assertEquals("TD00000105", updated.get("sessionId"));
      assertEquals("PENDING_SUBMIT", updated.get("status"));
      assertEquals("NU", updated.get("productGender"));
      var op = runtime.get("operations", "TD00000105");
      assertEquals(2, ((Number) op.get("Revision")).intValue());
      assertNull(op.get("JobId"));
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = mapper.readValue(op.get("Payload").toString(), Map.class);
      assertEquals("TD00000105-r2", payload.get("sessionId"));
      assertEquals(newPerson, payload.get("personAssetId"));
      assertTrue(executedSql.stream().anyMatch(s -> s.contains("UPDATE PhienThuDoAo")));

      // Không cho đổi ảnh khi phiên đang chờ xử lý
      Problem err =
          assertThrows(
              Problem.class,
              () ->
                  aiService.replacePerson(
                      "TD00000105", "ND00000027", new AiController.ReplacePerson(newPerson, null, true)));
      assertEquals(409, err.status);
      assertEquals("SESSION_NOT_REPLACEABLE", err.code);
    }
  }

  @Test
  void promoteResultCreatesPersonAssetAndBlocksDress(@TempDir Path temp) throws Exception {
    var mapper = JsonMapper.builder().build();
    try (var runtime = new RuntimeState(temp.resolve("runtime").toString(), mapper)) {
      var sessionRow = new LinkedHashMap<String, Object>();
      Db db =
          new Db(null, runtime) {
            @Override
            public Map<String, Object> one(String sql, Object... args) {
              if (sql.contains("FROM NguoiDung")) return Map.of("MaNguoiDung", args[0]);
              return new LinkedHashMap<>(sessionRow);
            }

            @Override
            public List<Map<String, Object>> rows(String sql, Object... args) {
              if (sql.contains("FROM DanhMuc") && "DM000004".equals(args[0])) {
                return List.of(Map.of("MaDanhMucCha", "DM000002"));
              }
              return List.of();
            }
          };

      var media = new MediaService(temp.resolve("storage").toString(), db, null, null, "");
      String resultAsset = media.save("ND00000027", "result", samplePng(), false, false).get("assetId").toString();
      var aiService = new AiService(db, null, media, mapper, new Idempotency(db, mapper), false);

      // Chặn Đầm liền (dress)
      sessionRow.putAll(
          Map.of(
              "sessionId", "TD00000200",
              "status", "THANH_CONG",
              "resultAssetId", resultAsset,
              "personFrame", "FULL_BODY",
              "vtonCategory", "dress",
              "categoryId", "DM000007"));
      Problem dressErr =
          assertThrows(Problem.class, () -> aiService.promoteResultToPerson("TD00000200", "ND00000027"));
      assertEquals(422, dressErr.status);
      assertEquals("CHAIN_DRESS_FORBIDDEN", dressErr.code);

      // Cho phép upper_body và tạo asset role=person mới
      sessionRow.put("vtonCategory", "upper_body");
      sessionRow.put("categoryId", "DM000004");
      var promoted = aiService.promoteResultToPerson("TD00000200", "ND00000027");
      String promotedId = promoted.get("personAssetId").toString();
      assertNotEquals(resultAsset, promotedId);
      assertEquals("person", media.owned(promotedId, "ND00000027").get("RoleName"));
      assertEquals("upper_body", promoted.get("sourceCategory"));
      assertEquals("FULL_BODY", promoted.get("personFrame"));
      assertEquals("NU", promoted.get("productGender"));
      assertEquals("TD00000200", media.sourceSessionId(promotedId));
    }
  }

  @Test
  void chainedSessionExposesPairedItemFromPreviousSession(@TempDir Path temp) throws Exception {
    var mapper = JsonMapper.builder().build();
    try (var runtime = new RuntimeState(temp.resolve("runtime").toString(), mapper)) {
      Db db =
          new Db(null, runtime) {
            @Override
            public Map<String, Object> one(String sql, Object... args) {
              if (sql.contains("FROM NguoiDung")) return Map.of("MaNguoiDung", args[0]);
              return RuntimeState.row(
                  "sessionId", "TD00000302",
                  "variantId", "BT00000020",
                  "productId", "SP00000010",
                  "categoryId", "DM000006",
                  "productName", "Quần Tây Ống Đứng",
                  "vtonCategory", "lower_body",
                  "sku", "SKU-QT-10",
                  "size", "M",
                  "colorName", "Đen",
                  "colorHex", "#111827",
                  "status", "THANH_CONG",
                  "personAssetId", "promoted-person");
            }

            @Override
            public List<Map<String, Object>> rows(String sql, Object... args) {
              if (sql.contains("FROM PhienThuDoAo") && args.length >= 2 && "TD00000301".equals(args[1])) {
                return List.of(
                    Map.of(
                        "sessionId", "TD00000301",
                        "variantId", "BT00000001",
                        "productId", "SP00000001",
                        "productName", "Áo Sơ Mi Lụa",
                        "vtonCategory", "upper_body",
                        "sku", "SKU-SM-01",
                        "size", "S",
                        "colorName", "Trắng Ngà",
                        "colorHex", "#FAF6F0",
                        "productImageUrl", "/api/v1/catalog/images/HA01/content?kind=original"));
              }
              return List.of();
            }
          };

      var media = new MediaService(temp.resolve("storage").toString(), db, null, null, "");
      runtime.put(
          "operations",
          "TD00000302",
          RuntimeState.row(
              "OperationId", "TD00000302",
              "OwnerId", "ND00000027",
              "KindName", "TRY_ON",
              "ResourceId", "TD00000302",
              "SourceSessionId", "TD00000301",
              "StateName", "COMPLETED",
              "Simulated", false));

      var aiService = new AiService(db, null, media, mapper, new Idempotency(db, mapper), false);
      var sessionB = aiService.session("TD00000302", "ND00000027");
      assertEquals("TD00000301", sessionB.get("sourceSessionId"));
      @SuppressWarnings("unchecked")
      Map<String, Object> paired = (Map<String, Object>) sessionB.get("pairedItem");
      assertNotNull(paired);
      assertEquals("SP00000001", paired.get("productId"));
      assertEquals("SKU-SM-01", paired.get("sku"));
      assertEquals("S", paired.get("size"));
    }
  }
}
