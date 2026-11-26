package com.yofaso.shop.order;

import com.yofaso.shop.common.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Các màn hình nội bộ chỉ đọc; vẫn dùng bảng/cột ERD gốc và giới hạn theo vai trò. */
@RestController
@RequestMapping("/api/v1")
public class StaffLookupController {
  private final Db db;
  private final StaffController staff;
  public StaffLookupController(Db db,StaffController staff) {this.db=db;this.staff=staff;}
  /** Hồ sơ user cho admin quản lý; không trả password hash/token. */
  @GetMapping("/admin/users/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String,Object> user(@PathVariable String id) {
    var result=db.one("SELECT MaNguoiDung AS userId,TenDangNhap AS username,HoVaTen AS fullName,SoDienThoai AS phone,Email AS email,TrangThaiHoatDong AS status FROM NguoiDung WHERE MaNguoiDung=?",id);
    result.put("roles",db.rows("SELECT v.MaVaiTro AS roleId,v.TenVaiTro AS name,v.TenHienThi AS label FROM VaiTro v JOIN NguoiDung_VaiTro r ON r.MaVaiTro=v.MaVaiTro WHERE r.MaNguoiDung=?",id));
    return result;
  }
  /** Admin sửa thông tin cơ bản; email/số điện thoại không truyền giữ nguyên, role có API riêng. */
  @PatchMapping("/admin/users/{id}")
  @org.springframework.transaction.annotation.Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String,Object> editUser(@PathVariable String id,
      @jakarta.validation.Valid @RequestBody com.yofaso.shop.auth.AuthController.Profile b,Authentication a) {
    String email=com.yofaso.shop.auth.AccountEmail.reserve(db,b.email(),id);
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",id);
    String gender = (b.gender() != null && Set.of("NAM", "NU", "KHAC").contains(b.gender().toUpperCase(java.util.Locale.ROOT)))
        ? b.gender().toUpperCase(java.util.Locale.ROOT) : null;
    db.exec("UPDATE NguoiDung SET HoVaTen=?,Email=COALESCE(?,Email),SoDienThoai=COALESCE(?,SoDienThoai),GioiTinh=COALESCE(?,GioiTinh) WHERE MaNguoiDung=?",b.fullName(),email,b.phone(),gender,id);
    db.runtime().bump(id);
    db.audit(a.getName(),"USER_PROFILE",id);
    return user(id);
  }
  /** Danh sách ca của thu ngân hiện tại; admin xem tất cả, lọc trạng thái và phân trang. */
  @GetMapping("/pos/shifts")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public List<Map<String,Object>> shifts(Authentication a,@RequestParam(defaultValue="") String status,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    Problem.require(status.isEmpty() || Set.of("DANG_MO","DA_DONG_CHO_DUYET","DA_PHE_DUYET_BAN_GIAO").contains(status),400,"FILTER_INVALID","Trạng thái ca không hợp lệ.");
    boolean admin=a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_SHOP_ADMIN"));
    return db.rows("SELECT MaCaLamViec AS shiftId,MaThuNgan AS cashierId,ThoiGianMoCa AS openedAt,ThoiGianDongCa AS closedAt,TrangThaiCa AS status,TienMatDauCa AS openingCash,TienMatKiemDemThucTe AS countedCash FROM CaLamViecThuNgan WHERE (? OR MaThuNgan=?) AND (?='' OR TrangThaiCa=?) ORDER BY ThoiGianMoCa DESC,MaCaLamViec LIMIT ? OFFSET ?",admin,a.getName(),status,status,size,Db.offset(page,size));
  }
  /** Dữ liệu hóa đơn từ snapshot đơn; UI/browser in, API không giả lập máy in vật lý. */
  @GetMapping("/pos/orders/{id}/receipt")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public Map<String,Object> receipt(@PathVariable String id,Authentication a) {
    var result=staff.staffOrder(a,id);
    Problem.require("TAI_QUAY_POS".equals(result.get("channel")),404,"NOT_FOUND","Không phải hóa đơn tại quầy.");
    return result;
  }
  /** Pick list gồm SKU, số lượng và vị trí kệ; không sửa tồn kho khi đọc. */
  @GetMapping("/staff/orders/{id}/pick-list")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public List<Map<String,Object>> pickList(@PathVariable String id,Authentication a) {
    staff.staffOrder(a,id);
    return db.rows("SELECT c.MaBienThe AS variantId,b.MaQuanLyHangHoa AS sku,c.TenSanPhamLucDatHang AS name,c.TenMauLucDatHang AS colorName,c.KichCoLucDatHang AS size,c.SoLuong AS quantity,b.ViTriGiaKe AS shelf FROM ChiTietDonHang c JOIN BienTheSanPham b ON b.MaBienThe=c.MaBienThe WHERE c.MaDonHang=? ORDER BY b.ViTriGiaKe,b.MaQuanLyHangHoa",id);
  }
  /** O2O: tra danh sách 5 phiên thử đồ gần nhất của khách theo số điện thoại; không mở ảnh riêng tư. */
  @GetMapping("/pos/try-on/sessions")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public List<Map<String, Object>> tryOnByPhone(@RequestParam String phone) {
    Problem.require(phone != null && phone.matches("[0-9+]{9,15}"), 400, "PHONE_INVALID", "Số điện thoại không hợp lệ.");
    var sessions =
        db.rows(
            """
            SELECT p.MaPhienThuDo AS sessionId,p.MaBienThe AS variantId,s.MaSanPham AS productId,
              s.TenSanPham AS productName,b.MaQuanLyHangHoa AS sku,b.KichCo AS size,
              b.TenMau AS colorName,b.MaMauSac AS colorHex,COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS price,
              b.SoLuongTonKho AS stock,COALESCE(b.ViTriGiaKe,'KHO-01') AS shelf,
              n.HoVaTen AS customerName,n.SoDienThoai AS customerPhone,n.MaNguoiDung AS customerId,
              p.ThoiGianKhoiTao AS createdAt,p.TrangThaiPhien AS status,COALESCE(p.DiemTongThe,0) AS total,
              p.DiemTiLeVocDang AS proportion,p.DiemHoaHopMauSac AS color,p.DiemCanBangHoaTiet AS pattern,
              p.LoiNhanXet AS advice,
              COALESCE(
                (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
                 FROM HinhAnhSanPham h WHERE h.MaSanPham=s.MaSanPham AND h.MaMauSac=b.MaMauSac
                   AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
                 ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
                (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
                 FROM HinhAnhSanPham h WHERE h.MaSanPham=s.MaSanPham
                 ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi,h.MaHinhAnh LIMIT 1)
              ) AS productImageUrl
            FROM PhienThuDoAo p JOIN NguoiDung n ON n.MaNguoiDung=p.MaNguoiDung
              JOIN BienTheSanPham b ON b.MaBienThe=p.MaBienThe
              JOIN SanPham s ON s.MaSanPham=b.MaSanPham
            WHERE n.SoDienThoai=?
            ORDER BY p.ThoiGianKhoiTao DESC LIMIT 5
            """,
            phone.trim());
    for (var row : sessions) {
      row.put("recommendations", enrichedRecommendations(Db.text(row, "sessionId")));
    }
    return sessions;
  }

  /** O2O: tra phiên theo mã phiên (và SĐT nếu có; nếu truyền SĐT thì phải khớp để giữ bảo mật). */
  @GetMapping("/pos/try-on/sessions/{id}")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public Map<String, Object> tryOn(@PathVariable String id, @RequestParam(required = false) String phone) {
    boolean hasPhone = phone != null && !phone.isBlank();
    if (hasPhone) {
      Problem.require(phone.matches("[0-9+]{9,15}"), 400, "PHONE_INVALID", "Số điện thoại không hợp lệ.");
    }
    String baseSql =
        """
        SELECT p.MaPhienThuDo AS sessionId,p.MaBienThe AS variantId,s.MaSanPham AS productId,
          s.TenSanPham AS productName,b.MaQuanLyHangHoa AS sku,b.KichCo AS size,
          b.TenMau AS colorName,b.MaMauSac AS colorHex,COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS price,
          b.SoLuongTonKho AS stock,COALESCE(b.ViTriGiaKe,'KHO-01') AS shelf,
          n.HoVaTen AS customerName,n.SoDienThoai AS customerPhone,n.MaNguoiDung AS customerId,
          COALESCE(
            (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
             FROM HinhAnhSanPham h WHERE h.MaSanPham=s.MaSanPham AND h.MaMauSac=b.MaMauSac
               AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
             ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
            (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
             FROM HinhAnhSanPham h WHERE h.MaSanPham=s.MaSanPham
             ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi,h.MaHinhAnh LIMIT 1)
          ) AS productImageUrl,
          p.TrangThaiPhien AS status,COALESCE(p.DiemTongThe,0) AS total,
          p.DiemTiLeVocDang AS proportion,p.DiemHoaHopMauSac AS color,p.DiemCanBangHoaTiet AS pattern,
          p.LoiNhanXet AS advice
        FROM PhienThuDoAo p JOIN NguoiDung n ON n.MaNguoiDung=p.MaNguoiDung
          JOIN BienTheSanPham b ON b.MaBienThe=p.MaBienThe
          JOIN SanPham s ON s.MaSanPham=b.MaSanPham
        WHERE p.MaPhienThuDo=?
        """;
    var result = hasPhone
        ? db.one(baseSql + " AND n.SoDienThoai=?", id.trim(), phone.trim())
        : db.one(baseSql, id.trim());
    var op = db.runtime().findById("operations", id.trim());
    String sourceSessionId = op == null ? "" : Db.text(op, "SourceSessionId");
    if (!sourceSessionId.isBlank() && !sourceSessionId.equals(id.trim())) {
      var linked = db.rows("""
          SELECT p.MaPhienThuDo AS sessionId,p.MaBienThe AS variantId,s.MaSanPham AS productId,
            s.TenSanPham AS productName,b.MaQuanLyHangHoa AS sku,b.KichCo AS size,
            b.TenMau AS colorName,b.MaMauSac AS colorHex,COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS price,
            b.SoLuongTonKho AS stock,COALESCE(b.ViTriGiaKe,'KHO-01') AS shelf,
            COALESCE(
              (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
               FROM HinhAnhSanPham h WHERE h.MaSanPham=s.MaSanPham AND h.MaMauSac=b.MaMauSac
                 AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
               ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
              (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
               FROM HinhAnhSanPham h WHERE h.MaSanPham=s.MaSanPham
               ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi,h.MaHinhAnh LIMIT 1)
            ) AS productImageUrl
          FROM PhienThuDoAo p JOIN NguoiDung n ON n.MaNguoiDung=p.MaNguoiDung
            JOIN BienTheSanPham b ON b.MaBienThe=p.MaBienThe
            JOIN SanPham s ON s.MaSanPham=b.MaSanPham
          WHERE p.MaPhienThuDo=?
          """, sourceSessionId);
      if (!linked.isEmpty()) {
        result.put("sourceSessionId", sourceSessionId);
        result.put("pairedItem", linked.getFirst());
      }
    }
    result.put("recommendations", enrichedRecommendations(id.trim()));
    return result;
  }

  private List<Map<String, Object>> enrichedRecommendations(String sessionId) {
    return db.rows(
        """
        SELECT g.MaGoiY AS recommendationId,g.MaBienTheGoiY AS variantId,
          b.MaSanPham AS productId,b.MaQuanLyHangHoa AS sku,s.TenSanPham AS productName,
          b.KichCo AS size,b.TenMau AS colorName,COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS price,
          b.SoLuongTonKho AS stock,COALESCE(b.ViTriGiaKe,'KHO-01') AS shelf,
          g.DiemSauKhiPhoiKem AS projectedScore,g.LyDoGoiY AS reason,g.TrangThaiTuongTac AS status,
          (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
           FROM HinhAnhSanPham h WHERE h.MaSanPham=s.MaSanPham
           ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi ASC LIMIT 1) AS imageUrl
        FROM GoiYPhoiDo g
        JOIN BienTheSanPham b ON b.MaBienThe=g.MaBienTheGoiY
        JOIN SanPham s ON s.MaSanPham=b.MaSanPham
        WHERE g.MaPhienThuDo=?
        ORDER BY g.DiemSauKhiPhoiKem DESC
        """,
        sessionId);
  }
}
