package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.yofaso.shop.ai.*;
import com.yofaso.shop.common.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Hợp đồng đồng bộ/R2 với ảnh tổng hợp; không tuyên bố đã chạy model hoặc R2 thật. */
class CatalogAnalysisContractTest {
  @TempDir Path temporary;
  @Test @SuppressWarnings("unchecked") void allMasksAreUploadedAndInputOrderSurvivesReversedAiResponse() throws Exception {
    var mapper=JsonMapper.builder().build();
    try(var runtime=new RuntimeState(temporary.resolve("registry").toString(),mapper)) {
      var db=mock(Db.class);
      when(db.runtime()).thenReturn(runtime);
      when(db.one(anyString(),any())).thenReturn(new HashMap<>());
      when(db.count(anyString(),any())).thenReturn(1L);
      var client=mock(AiClient.class);
      var r2=mock(R2Storage.class);
      when(r2.upload(anyString(),any())).thenAnswer(i -> i.getArgument(0));
      var media=new MediaService(temporary.resolve("images").toString(),db,client,r2,"");
      var output=new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(128,192,BufferedImage.TYPE_INT_RGB),"png",output);
      byte[] png=output.toByteArray();
      when(client.upload(any(),eq("garment"))).thenAnswer(i -> Map.of("assetId",UUID.randomUUID().toString().replace("-","")));
      when(client.download(anyString())).thenReturn(png);
      String first=media.save("owner","garment",png,false).get("assetId").toString();
      String second=media.save("owner","garment",png,false).get("assetId").toString();
      var keys=new Idempotency(db,mapper);
      var service=new AiService(db,client,media,mapper,keys,false);
      var operation=service.analyze("owner","analysis-mask-key","SPTEST",new AiController.Analysis(List.of(first,second),List.of()));
      String id=operation.get("operationId").toString();
      when(client.post(eq("/internal/v1/products/analyze"),any())).thenAnswer(i -> {
        var request=(Map<String,Object>)i.getArgument(1);
        var sources=(List<String>)request.get("productAssetIds");
        var variants=new ArrayList<Map<String,Object>>();
        for(int n=1;n>=0;n--) variants.add(RuntimeState.row("sourceAssetId",sources.get(n),"category","short_sleeved_shirt",
          "cutout",Map.of("assetId","cutout"+n),"mask",Map.of("assetId","mask"+n)));
        return Map.of("jobId","testJob","status","COMPLETED","simulated",false,
          "result",Map.of("vtonCategory","upper_body","variants",variants));
      });
      service.processDue();
      var completed=service.operation(id,"owner");
      assertEquals("COMPLETED",completed.get("status"));
      var result=(Map<String,Object>)completed.get("result");
      var variants=(List<Map<String,Object>>)result.get("variants");
      assertEquals(first,variants.getFirst().get("sourceAssetId"));
      assertTrue(variants.getFirst().get("maskKey").toString().endsWith("--img01--v01--mask.png"));
      var capture=ArgumentCaptor.forClass(String.class);
      verify(r2,times(6)).upload(capture.capture(),any());
      var uploaded=capture.getAllValues();
      assertEquals(6,new HashSet<>(uploaded).size());
      assertEquals(2,uploaded.stream().filter(k -> k.endsWith("--mask.png")).count());
      assertTrue(uploaded.stream().allMatch(k -> k.contains("/"+id+"/")));
      // Khởi tạo adapter đọc lại manifest vẫn thấy operation và mapping ảnh sau restart.
      assertEquals(first,runtime.get("media",first).get("AssetId"));
    }
    try(var reopened=new RuntimeState(temporary.resolve("registry").toString(),mapper)) {
      assertEquals(1,reopened.find("operations",r -> "COMPLETED".equals(r.get("StateName"))).size());
    }
  }
}
