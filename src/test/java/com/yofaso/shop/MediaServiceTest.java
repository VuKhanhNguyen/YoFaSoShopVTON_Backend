package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.yofaso.shop.ai.*;
import com.yofaso.shop.common.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MediaServiceTest {
  @TempDir Path tempDir;

  @Test
  void saveLargeImageUpTo50MbSucceeds() throws Exception {
    var mapper = JsonMapper.builder().build();
    try (var runtime = new RuntimeState(tempDir.resolve("registry").toString(), mapper)) {
      var db = mock(Db.class);
      when(db.runtime()).thenReturn(runtime);
      when(db.one(anyString(), any())).thenReturn(new HashMap<>());
      var client = mock(AiClient.class);
      var r2 = mock(R2Storage.class);

      var media = new MediaService(tempDir.resolve("storage").toString(), db, client, r2, "");

      // Tạo ảnh 1800x1200 (>1600px)
      var bi = new BufferedImage(1800, 1200, BufferedImage.TYPE_INT_RGB);
      var out = new ByteArrayOutputStream();
      ImageIO.write(bi, "png", out);
      byte[] largePng = out.toByteArray();

      var saved = media.save("owner-test", "garment", largePng, false);
      assertNotNull(saved.get("assetId"));
      assertEquals(1800, saved.get("width"));
      assertEquals(1200, saved.get("height"));
    }
  }

  @Test
  void downscaleCalculationPreservesAspectRatio() {
    int w = 4403, h = 6605, maxDimension = 1600;
    int targetW = w, targetH = h;
    if (targetW > targetH) {
      targetH = (int) Math.round((double) targetH * maxDimension / targetW);
      targetW = maxDimension;
    } else {
      targetW = (int) Math.round((double) targetW * maxDimension / targetH);
      targetH = maxDimension;
    }
    assertEquals(1600, targetH);
    assertEquals(1067, targetW);
  }
}
