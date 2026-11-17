package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yofaso.shop.ai.MediaService;
import com.yofaso.shop.catalog.CatalogController;
import com.yofaso.shop.catalog.CatalogService;
import com.yofaso.shop.common.Db;
import com.yofaso.shop.order.InventoryService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Kiểm tra unit logic CRUD mới trên CatalogService (tồn kho ledger, xóa/ngừng SKU, VTON quick update). */
class CatalogCrudServiceTest {

  @Test
  void catalogFilesShareFolderAndPreserveImagePositions(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var db = mock(Db.class);
    var runtime = mock(com.yofaso.shop.common.RuntimeState.class);
    when(db.runtime()).thenReturn(runtime);
    var storage = mock(com.yofaso.shop.ai.R2Storage.class);
    when(storage.upload(anyString(), any())).thenAnswer(call -> call.getArgument(0));
    var media = spy(new MediaService(directory.toString(), db, mock(com.yofaso.shop.ai.AiClient.class), storage, ""));
    doReturn(new byte[] {1, 2, 3}).when(media).bytes("asset");
    String slug = "w-dam-Xuan-He";
    assertEquals("catalog/products/" + slug + "/" + slug + "--img02--v01--original.png",
        media.publishCatalog(slug, 2, "original", "asset"));
    assertEquals("catalog/products/" + slug + "/" + slug + "--img02--v01--cutout.png",
        media.publishCatalog(slug, 2, "cutout", "asset"));
    assertEquals("catalog-processing/products/" + slug + "/" + slug + "--img02--v01--mask.png",
        media.publishCatalog(slug, 2, "mask", "asset"));
    assertEquals("catalog-processing/products/" + slug + "/" + slug + "--ref01--v01--reference.png",
        media.publishCatalog(slug, 1, "reference", "asset"));
  }

  @Test
  void storageSlugUsesProductNameAndGender() {
    assertEquals("w-dam-Xuan-He-Co-duc-Nhun-Chan-2-Tang",
        MediaService.productSlug("đầm Xuân Hè Cổ đức Nhún Chân 2 Tầng", true));
    assertEquals("m-Ao-So-Mi", MediaService.productSlug("Áo Sơ Mi", false));
  }

  @Test
  void createVariantWithInitialStockRecordsLedgerMovement() {
    var db = mock(Db.class);
    var inventory = mock(InventoryService.class);
    var media = mock(MediaService.class);
    var service = new CatalogService(db, inventory, media);

    when(db.one(contains("FROM SanPham"), eq("SP00000001"))).thenReturn(Map.of("MaSanPham", "SP00000001"));
    when(db.one(contains("FROM BienTheSanPham"), anyString(), eq("SP00000001")))
        .thenReturn(Map.of("MaBienThe", "BT_MOCK", "SoLuongTonKho", 0));

    var req =
        new CatalogController.Variant(
            "SKU-TEST-M",
            "M",
            "Đen",
            "#1A1919",
            new BigDecimal("350000"),
            null,
            "KE-F02",
            "DANG_BAN",
            15);
    String id = service.variant("SP00000001", null, req, "ND00000001");

    assertNotNull(id);
    // Note: when id is null, Db.id("BT") generates an ID; inventory.move is called with delta 15
    verify(inventory)
        .move(anyString(), eq(15), eq("NHAP_KHO_BO_SUNG"), eq("SP00000001"), eq("ND00000001"), anyString());
  }

  @Test
  void deleteVariantDiscontinuesWhenReferencedByOrders() {
    var db = mock(Db.class);
    var inventory = mock(InventoryService.class);
    var media = mock(MediaService.class);
    var service = new CatalogService(db, inventory, media);

    when(db.rows(contains("FROM BienTheSanPham"), eq("BT00000001"), eq("SP00000001")))
        .thenReturn(List.of(Map.of("MaBienThe", "BT00000001")));
    when(db.count(contains("ChiTietDonHang"), eq("BT00000001"))).thenReturn(1L);
    when(db.count(contains("PhienThuDoAo"), eq("BT00000001"))).thenReturn(0L);
    when(db.count(contains("GoiYPhoiDo"), eq("BT00000001"))).thenReturn(0L);
    when(db.count(contains("BienDongKho"), eq("BT00000001"))).thenReturn(0L);

    var res = service.deleteVariant("SP00000001", "BT00000001", "ND00000001");
    assertEquals(false, res.get("deleted"));
    assertEquals(true, res.get("discontinued"));
    verify(db).exec(contains("TrangThai='NGUNG_BAN'"), eq("BT00000001"), eq("SP00000001"));
  }
}
