package com.yofaso.shop.scoring;

import com.yofaso.shop.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Lưu lịch sử gợi ý trong GoiYPhoiDo đã có; khóa phiên thay cho thêm unique index. */
@RestController
@RequestMapping("/api/v1/scoring")
public class RecommendationController {
  private final Db db;
  private final ScoringService scoring;
  public record Interaction(@NotNull @Pattern(regexp="DA_THU_THEM|DA_THEM_GIO_HANG|DA_MUA") String status) {}
  public RecommendationController(Db db, ScoringService scoring) { this.db=db; this.scoring=scoring; }

  private Map<String,Object> session(String id, String owner) {
    return db.one("SELECT MaBienThe,TrangThaiPhien FROM PhienThuDoAo WHERE MaPhienThuDo=? AND MaNguoiDung=? FOR UPDATE",id,owner);
  }
  private void persistRecommendations(String sessionId, String variantId, boolean tucked) {
    for (var suggestion : scoring.recommend(variantId, tucked)) {
      String sku = suggestion.get("variantId").toString();
      var old = db.rows("SELECT MaGoiY FROM GoiYPhoiDo WHERE MaPhienThuDo=? AND MaBienTheGoiY=?", sessionId, sku);
      if (old.isEmpty()) {
        db.exec(
            "INSERT INTO GoiYPhoiDo(MaGoiY,MaPhienThuDo,MaBienTheGoiY,DiemSauKhiPhoiKem,LyDoGoiY) VALUES(?,?,?,?,?)",
            Db.id("GY"),
            sessionId,
            sku,
            suggestion.get("projectedScore"),
            suggestion.get("reason"));
      }
    }
  }

  /** Quét hàng còn bán, chỉ ghi điểm >=85; gọi lại không xóa lịch sử tương tác. */
  @PostMapping("/sessions/{id}/recommendations")
  @Transactional
  public List<Map<String,Object>> generate(@PathVariable String id,
      @RequestParam(defaultValue="false") boolean tucked, Authentication a) {
    var s=session(id,a.getName());
    Problem.require("THANH_CONG".equals(s.get("TrangThaiPhien")),409,"SESSION_NOT_SCOREABLE","Cần phiên thật thành công.");
    persistRecommendations(id, Db.text(s, "MaBienThe"), tucked);
    return fetchList(id);
  }

  /** Lịch sử riêng của chủ phiên; tự động sinh gợi ý nếu phiên đã thành công nhưng chưa có bản ghi. */
  @GetMapping("/sessions/{id}/recommendations")
  @Transactional
  public List<Map<String,Object>> list(@PathVariable String id, Authentication a) {
    var s = session(id,a.getName());
    var rows = fetchList(id);
    if (rows.isEmpty() && "THANH_CONG".equals(s.get("TrangThaiPhien"))) {
      persistRecommendations(id, Db.text(s, "MaBienThe"), false);
      rows = fetchList(id);
    }
    return rows;
  }

  private List<Map<String,Object>> fetchList(String id) {
    return db.rows("""
        SELECT g.MaGoiY AS recommendationId,g.MaBienTheGoiY AS variantId,
          p.MaSanPham AS productId,p.TenSanPham AS name,p.PhanLoaiVTON AS vtonCategory,
          b.KichCo AS size,b.TenMau AS colorName,b.MaMauSac AS colorHex,COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS price,
          COALESCE(
            (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
             FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham AND h.MaMauSac=b.MaMauSac
               AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
             ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
            (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
             FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham
             ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi,h.MaHinhAnh LIMIT 1)
          ) AS imageUrl,
          g.DiemSauKhiPhoiKem AS projectedScore,
          g.LyDoGoiY AS reason,g.TrangThaiTuongTac AS status
        FROM GoiYPhoiDo g JOIN BienTheSanPham b ON b.MaBienThe=g.MaBienTheGoiY
          JOIN SanPham p ON p.MaSanPham=b.MaSanPham
        WHERE g.MaPhienThuDo=? AND b.SoLuongTonKho>0 AND b.TrangThai='DANG_BAN'
          AND p.TrangThaiSanPham='DANG_KINH_DOANH'
        ORDER BY g.DiemSauKhiPhoiKem DESC,g.MaGoiY
        """, id);
  }
  /** Tương tác chỉ tiến lên; thêm giỏ/mua phải có dữ liệu thực của chủ phiên. */
  @PatchMapping("/recommendations/{id}/interaction")
  @Transactional
  public Map<String,Object> interact(@PathVariable String id, @Valid @RequestBody Interaction b, Authentication a) {
    var initial=db.one("SELECT MaPhienThuDo FROM GoiYPhoiDo WHERE MaGoiY=?",id);
    session(Db.text(initial,"MaPhienThuDo"),a.getName());
    var r=db.one("SELECT * FROM GoiYPhoiDo WHERE MaGoiY=? FOR UPDATE",id);
    String sku=Db.text(r,"MaBienTheGoiY");
    var states=List.of("CHUA_XEM","DA_THU_THEM","DA_THEM_GIO_HANG","DA_MUA");
    if(states.indexOf(b.status())<=states.indexOf(r.get("TrangThaiTuongTac"))) return Map.of("status",r.get("TrangThaiTuongTac"));
    if(b.status().equals("DA_THEM_GIO_HANG")) Problem.require(db.count("SELECT COUNT(*) FROM ChiTietGioHang c JOIN GioHang g ON g.MaGioHang=c.MaGioHang WHERE g.MaNguoiDung=? AND c.MaBienThe=?",a.getName(),sku)>0,409,"CART_EVIDENCE_REQUIRED","SKU chưa có trong giỏ của bạn.");
    if(b.status().equals("DA_MUA")) Problem.require(db.count("SELECT COUNT(*) FROM ChiTietDonHang c JOIN DonHang d ON d.MaDonHang=c.MaDonHang JOIN PhienThuDoAo p ON p.MaPhienThuDo=? WHERE d.MaNguoiDung=? AND c.MaBienThe=? AND d.TrangThaiDonHang='HOAN_TAT' AND d.NgayTao>=p.ThoiGianKhoiTao",r.get("MaPhienThuDo"),a.getName(),sku)>0,409,"PURCHASE_EVIDENCE_REQUIRED","Chưa có đơn hoàn tất phù hợp.");
    db.exec("UPDATE GoiYPhoiDo SET TrangThaiTuongTac=? WHERE MaGoiY=?",b.status(),id);
    return Map.of("status",b.status());
  }
}
