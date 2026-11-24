package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yofaso.shop.chat.OpenRouterClient;
import com.yofaso.shop.common.Db;
import com.yofaso.shop.scoring.ScoringService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScoringServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void evaluateUsesWorkbookRulesJsonWithoutHardcodedLabels() throws Exception {
    var db = mock(Db.class);
    var mapper = JsonMapper.builder().build();
    var service = new ScoringService(db, mapper);

    Map<String, Object> polo = Map.ofEntries(
        Map.entry("MaSanPham", "SP00000082"),
        Map.entry("MaBienThe", "BT_POLO"),
        Map.entry("MaDanhMuc", "DM000011"),
        Map.entry("PhanLoaiVTON", "upper_body"),
        Map.entry("ChieuDaiTrangPhuc", "AO_TIEU_CHUAN"),
        Map.entry("KieuDang", "OM_SAT"),
        Map.entry("LoaiCapQuan", "KHONG_AP_DUNG"),
        Map.entry("LoaiHoaTiet", "VAI_TRON"),
        Map.entry("TenMau", "Xanh navy"),
        Map.entry("MaMauSac", "#1B2A4A"));
    Map<String, Object> trousers = Map.ofEntries(
        Map.entry("MaSanPham", "SP_QUAN"),
        Map.entry("MaBienThe", "BT_QUAN"),
        Map.entry("MaDanhMuc", "DM000018"),
        Map.entry("PhanLoaiVTON", "lower_body"),
        Map.entry("ChieuDaiTrangPhuc", "DAI_CHAM_GOT"),
        Map.entry("KieuDang", "SUONG_VUA"),
        Map.entry("LoaiCapQuan", "CAP_CAO"),
        Map.entry("LoaiHoaTiet", "VAI_TRON"),
        Map.entry("TenMau", "Trắng"),
        Map.entry("MaMauSac", "#FFFFFF"),
        Map.entry("TieuLoaiTrangPhuc", "trousers"));
    Map<String, Object> dress = Map.ofEntries(
        Map.entry("MaSanPham", "SP_DRESS"),
        Map.entry("MaBienThe", "BT_DRESS"),
        Map.entry("MaDanhMuc", "DM000031"),
        Map.entry("PhanLoaiVTON", "dress"),
        Map.entry("ChieuDaiTrangPhuc", "MIDI_QUA_GOI"),
        Map.entry("KieuDang", "SUONG_VUA"),
        Map.entry("LoaiCapQuan", "KHONG_AP_DUNG"),
        Map.entry("LoaiHoaTiet", "VAI_TRON"),
        Map.entry("TenMau", "Đen"),
        Map.entry("MaMauSac", "#1A1A1A"));

    when(db.one(contains("FROM SanPham p JOIN BienTheSanPham b"), eq("BT_POLO"))).thenReturn(polo);
    when(db.one(contains("FROM SanPham p JOIN BienTheSanPham b"), eq("BT_QUAN"))).thenReturn(trousers);
    when(db.one(contains("FROM SanPham p JOIN BienTheSanPham b"), eq("BT_DRESS"))).thenReturn(dress);

    var res = service.evaluate("BT_POLO", "BT_QUAN", true);
    assertEquals(100, res.get("total"));
    assertEquals("Rất Tôn Dáng & Hài Hòa", res.get("tierLabel"));
    assertEquals("WORKBOOK_RULES_JSON", res.get("aiModelSource"));
    var breakdown = (Map<String, Map<String, Object>>) res.get("breakdown");
    assertTrue(breakdown.get("proportion").get("stylistNote").toString().contains("1/3 - 2/3"));
    assertTrue(breakdown.get("color").get("stylistNote").toString().contains("Xanh Navy"));
    assertFalse(breakdown.get("color").get("stylistNote").toString().contains("#"), "Không lộ mã HEX thô cho khách");
    var swatches = (List<Map<String, Object>>) res.get("colorSwatches");
    assertEquals(2, swatches.size());
    var recColors = (List<Map<String, Object>>) res.get("recommendedColors");
    assertFalse(recColors.isEmpty());

    // Kiểm tra lớp AI Stylist remake giữ nguyên điểm 40/40/20 và làm đẹp lời nhận xét + lọc HEX
    var mockLlm = mock(OpenRouterClient.class);
    when(mockLlm.isConfigured()).thenReturn(true);
    when(mockLlm.complete(anyList(), isNull())).thenReturn(Optional.of(
        new OpenRouterClient.CompletionResult(
            "“Bộ phối xanh navy cùng quần trắng (#FFFFFF) tạo phom dáng cao ráo và thanh lịch chuẩn quý ông.”",
            "nvidia/z-ai/glm-5.3-flash",
            true)));
    var aiService = new ScoringService(db, mapper, mockLlm);
    var enhanced = aiService.enhanceStylistEvaluation(res, "BT_POLO", "BT_QUAN", true, null);
    assertEquals(100, enhanced.get("total"));
    assertEquals(true, enhanced.get("stylistEnhanced"));
    assertFalse(enhanced.get("stylistComment").toString().contains("#FFFFFF"));
    assertTrue(enhanced.get("stylistComment").toString().contains("phom dáng cao ráo"));

    var dressRes = service.evaluate("BT_DRESS", null, false);
    assertEquals("PROP_06", dressRes.get("proportionRule"));
    assertTrue((int) dressRes.get("total") >= 90);

    var preview = service.singleItemPreview("BT_POLO");
    assertEquals(false, preview.get("evaluated"));
    assertEquals(true, preview.get("singleItem"));
    assertTrue(preview.get("colorNote").toString().contains("Xanh Navy"));

    when(db.rows(contains("SELECT MaDanhMucCha FROM DanhMuc"), eq("DM000011")))
        .thenReturn(List.of(Map.of("MaDanhMucCha", "DM000003")));
    when(db.rows(contains("SELECT MaDanhMucCha FROM DanhMuc"), eq("DM000003")))
        .thenReturn(List.of(Map.of("MaDanhMucCha", "DM000001")));
    when(db.rows(contains("FROM BienTheSanPham b JOIN SanPham p"), any(), any(), any(), any()))
        .thenReturn(List.of(Map.of(
            "MaBienThe", "BT_QUAN",
            "MaSanPham", "SP_QUAN",
            "TenSanPham", "Quần Tây Trắng",
            "TenMau", "Trắng",
            "price", new BigDecimal("490000"),
            "MaHinhAnh", "HA00000999")));

    var recs = service.recommend("BT_POLO", false);
    assertEquals(1, recs.size());
    assertEquals("/api/v1/catalog/images/HA00000999/content?kind=original", recs.getFirst().get("imageUrl"));
  }
}
