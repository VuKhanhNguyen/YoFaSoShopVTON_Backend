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
  /** Quét hàng còn bán, chỉ ghi điểm >=90; gọi lại không xóa lịch sử tương tác. */
  @PostMapping("/sessions/{id}/recommendations")
  @Transactional
  public List<Map<String,Object>> generate(@PathVariable String id,
      @RequestParam(defaultValue="false") boolean tucked, Authentication a) {
    var s=session(id,a.getName());
    Problem.require("THANH_CONG".equals(s.get("TrangThaiPhien")),409,"SESSION_NOT_SCOREABLE","Cần phiên thật thành công.");
    for(var suggestion:scoring.recommend(Db.text(s,"MaBienThe"),tucked)) {
      String sku=suggestion.get("variantId").toString();
      var old=db.rows("SELECT MaGoiY FROM GoiYPhoiDo WHERE MaPhienThuDo=? AND MaBienTheGoiY=?",id,sku);
      if(old.isEmpty()) db.exec("INSERT INTO GoiYPhoiDo(MaGoiY,MaPhienThuDo,MaBienTheGoiY,DiemSauKhiPhoiKem,LyDoGoiY) VALUES(?,?,?,?,?)",
          Db.id("GY"),id,sku,suggestion.get("projectedScore"),"Ước tính quy tắc catalog >=90; sơ vin="+tucked+". Không phải điểm đo từ ảnh.");
    }
    return list(id,a);
  }
  /** Lịch sử riêng của chủ phiên; không coi tồn kho lúc gợi ý là cam kết giữ hàng. */
  @GetMapping("/sessions/{id}/recommendations")
  @Transactional
  public List<Map<String,Object>> list(@PathVariable String id, Authentication a) {
    session(id,a.getName());
    return db.rows("SELECT MaGoiY AS recommendationId,MaBienTheGoiY AS variantId,DiemSauKhiPhoiKem AS projectedScore,LyDoGoiY AS reason,TrangThaiTuongTac AS status FROM GoiYPhoiDo WHERE MaPhienThuDo=? ORDER BY DiemSauKhiPhoiKem DESC,MaGoiY",id);
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
