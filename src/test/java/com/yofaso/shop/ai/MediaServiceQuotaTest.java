package com.yofaso.shop.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.common.RuntimeState;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MediaServiceQuotaTest {

  private static byte[] samplePng() throws Exception {
    var img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  @Test
  void shopPersonReusesCachedAssetAndQuotaExcludesShopModelsAndR2Snapshots(@TempDir Path temp) throws Exception {
    var mapper = JsonMapper.builder().build();
    Path runtimeDir = temp.resolve("runtime");
    Files.createDirectories(runtimeDir);
    var seeded = new LinkedHashMap<String, Map<String, Object>>();
    for (int i = 0; i < 110; i++) {
      String r2Key = "tryon/TD" + i + "/p--person.png";
      seeded.put(r2Key, RuntimeState.row("AssetId", r2Key, "OwnerId", "ND00000027", "RoleName", "person", "LocalFile", "x.png", "R2Key", r2Key));
      seeded.put("g" + i, RuntimeState.row("AssetId", "g" + i, "OwnerId", "ND00000001", "RoleName", "garment"));
      seeded.put("p" + i, RuntimeState.row("AssetId", "p" + i, "OwnerId", "ND00000001", "RoleName", "person"));
    }
    Files.writeString(runtimeDir.resolve("media.json"), mapper.writeValueAsString(seeded));

    try (var runtime = new RuntimeState(runtimeDir.toString(), mapper)) {
      Db db = new Db(null, runtime) {
        @Override
        public Map<String, Object> one(String sql, Object... args) {
          return Map.of("MaNguoiDung", args[0]);
        }
      };
      var media = new MediaService(temp.resolve("storage").toString(), db, null, null, "");

      String first = media.shopPerson("ND00000027", "female");
      String second = media.shopPerson("ND00000027", "female");
      assertEquals(first, second, "shopPerson phải tái sử dụng ảnh mẫu đã lưu thay vì tạo bản trùng");

      var uploaded = media.save("ND00000027", "person", samplePng(), false);
      assertNotNull(uploaded.get("assetId"), "Ảnh R2 snapshot và ảnh mẫu shop không được chiếm quota 100 ảnh upload");

      // Admin / catalog uploads (garment, reference, hoặc enforceQuota=false) không bị giới hạn 100 ảnh
      assertNotNull(media.save("ND00000001", "garment", samplePng(), false).get("assetId"));
      assertNotNull(media.save("ND00000001", "reference", samplePng(), false).get("assetId"));
      assertNotNull(media.save("ND00000001", "person", samplePng(), false, false).get("assetId"));
    }
  }

  @Test
  void remoteReuploadsWhenCachedAiAssetReturns404(@TempDir Path temp) throws Exception {
    try (var runtime = new RuntimeState(temp.resolve("runtime").toString(), JsonMapper.builder().build())) {
      Db db = new Db(null, runtime) {
        @Override
        public Map<String, Object> one(String sql, Object... args) {
          return Map.of("MaNguoiDung", args[0]);
        }
      };
      var ai = mock(AiClient.class);
      var media = new MediaService(temp.resolve("storage").toString(), db, ai, null, "");

      String localId = media.save("ND00000027", "person", samplePng(), false).get("assetId").toString();
      String staleRemote = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
      String freshRemote = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
      runtime.patch("media", localId, "AiAssetId", staleRemote);

      when(ai.getVton("/internal/v1/assets/" + staleRemote))
          .thenThrow(new Problem(404, "AI_HTTP_404", "Chưa tìm thấy tài nguyên AI."));
      when(ai.upload(any(), eq("person"))).thenReturn(Map.of("assetId", freshRemote));

      assertEquals(freshRemote, media.remote(localId));
      assertEquals(freshRemote, runtime.get("media", localId).get("AiAssetId"));
      verify(ai, times(1)).upload(any(), eq("person"));
    }
  }
}
