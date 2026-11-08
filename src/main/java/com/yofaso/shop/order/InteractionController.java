package com.yofaso.shop.order;

import com.yofaso.shop.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Đánh giá verified buyer và thông báo riêng của người dùng. */
@RestController
@RequestMapping("/api/v1")
public class InteractionController {
  public record Review(
      @NotBlank @Size(max = 20) String orderLineId,
      @Min(1) @Max(5) int stars,
      @NotBlank @Size(max = 3000) String comment) {}

  public record Moderation(
      @NotNull @Pattern(regexp = "CHO_DUYET|DA_HIEN_THI|DA_AN_VI_PHAM") String status,
      @Size(max = 3000) String reply) {}

  private final Db db;

  /** Nhận DB để kiểm tra giao dịch mua trước khi nhận đánh giá. */
  public InteractionController(Db db) {
    this.db = db;
  }

  /** Chỉ hiển thị đánh giá đã duyệt. */
  @GetMapping("/catalog/products/{id}/reviews")
  public List<Map<String, Object>> reviews(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var rows = db.rows(
        "SELECT d.MaDanhGia AS reviewId, d.SoSao AS stars, d.NoiDungBinhLuan AS comment, d.PhanHoiTuShop AS shopReply, "
            + "d.NgayDanhGia AS createdAt, d.HinhAnhThucTe AS photoUrl, u.HoVaTen AS userName, u.AnhDaiDien AS userAvatar, "
            + "b.KichCo AS size, b.TenMau AS colorName, b.MaQuanLyHangHoa AS sku "
            + "FROM DanhGiaSanPham d "
            + "JOIN NguoiDung u ON u.MaNguoiDung = d.MaNguoiDung "
            + "LEFT JOIN ChiTietDonHang c ON c.MaChiTietDonHang = d.MaChiTietDonHang "
            + "LEFT JOIN BienTheSanPham b ON b.MaBienThe = c.MaBienThe "
            + "WHERE d.MaSanPham = ? AND d.TrangThaiDuyet = 'DA_HIEN_THI' "
            + "ORDER BY d.NgayDanhGia DESC LIMIT ? OFFSET ?",
        id,
        size,
        Db.offset(page, size));
    for (var r : rows) {
      if (r.get("userAvatar") != null) {
        String av = r.get("userAvatar").toString();
        if (!av.startsWith("http") && !av.startsWith("/")) {
          r.put("userAvatar", "/api/v1/content?key=" + av);
        }
      }
    }
    return rows;
  }

  /** Mỗi dòng đơn đã giao chỉ được đánh giá một lần nhờ unique constraint. */
  @PostMapping("/reviews")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> review(Authentication a, @Valid @RequestBody Review b) {
    var line =
        db.one(
            "SELECT b.MaSanPham FROM ChiTietDonHang c JOIN DonHang d ON d.MaDonHang=c.MaDonHang"
                + " JOIN BienTheSanPham b ON b.MaBienThe=c.MaBienThe WHERE c.MaChiTietDonHang=? AND"
                + " d.MaNguoiDung=? AND d.TrangThaiDonHang='HOAN_TAT'",
            b.orderLineId(),
            a.getName());
    String id = Db.id("DG");
    db.exec(
        "INSERT INTO"
            + " DanhGiaSanPham(MaDanhGia,MaSanPham,MaNguoiDung,MaChiTietDonHang,SoSao,NoiDungBinhLuan,TrangThaiDuyet)"
            + " VALUES(?,?,?,?,?,?,'CHO_DUYET')",
        id,
        line.get("MaSanPham"),
        a.getName(),
        b.orderLineId(),
        b.stars(),
        b.comment());
    return Map.of("reviewId", id, "status", "CHO_DUYET");
  }

  /** Chủ shop đọc danh sách đánh giá từ khách hàng kèm tên sản phẩm và tên người dùng. */
  @GetMapping("/admin/reviews")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> pending(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return db.rows(
        "SELECT d.MaDanhGia AS reviewId, d.MaSanPham AS productId, s.TenSanPham AS productName, "
            + "COALESCE(u.HoVaTen, 'Khách hàng') AS customerName, d.MaChiTietDonHang AS lineId, "
            + "ct.MaDonHang AS orderId, "
            + "d.SoSao AS stars, d.NoiDungBinhLuan AS comment, d.PhanHoiTuShop AS reply, "
            + "d.TrangThaiDuyet AS status, d.HinhAnhThucTe AS photoUrl, d.NgayDanhGia AS createdAt, "
            + "CASE WHEN d.MaChiTietDonHang IS NOT NULL THEN TRUE ELSE FALSE END AS verified "
            + "FROM DanhGiaSanPham d "
            + "LEFT JOIN SanPham s ON s.MaSanPham = d.MaSanPham "
            + "LEFT JOIN NguoiDung u ON u.MaNguoiDung = d.MaNguoiDung "
            + "LEFT JOIN ChiTietDonHang ct ON ct.MaChiTietDonHang = d.MaChiTietDonHang "
            + "ORDER BY d.NgayDanhGia DESC LIMIT ? OFFSET ?",
        size,
        Db.offset(page, size));
  }

  /** Duyệt/ẩn và phản hồi từ shop. */
  @PatchMapping("/admin/reviews/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> moderate(
      @PathVariable String id, @Valid @RequestBody Moderation b, Authentication a) {
    Problem.require(
        db.exec(
                "UPDATE DanhGiaSanPham SET TrangThaiDuyet=?,PhanHoiTuShop=? WHERE MaDanhGia=?",
                b.status(),
                b.reply(),
                id)
            == 1,
        404,
        "NOT_FOUND",
        "Không có đánh giá.");
    db.audit(a.getName(), "REVIEW_MODERATE", id);
    return Map.of("updated", true);
  }

  /** Thông báo của người đăng nhập, hỗ trợ lọc chưa đọc. */
  @GetMapping("/notifications")
  public List<Map<String, Object>> notifications(
      Authentication a,
      @RequestParam(defaultValue = "false") boolean unread,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return db.rows(
        "SELECT MaThongBao AS notificationId,TieuDe AS title,NoiDung AS message,LoaiThongBao AS"
            + " type,LienKetDieuHuong AS link,DaDoc AS readStatus,NgayTao AS createdAt FROM"
            + " ThongBao WHERE MaNguoiDung=? AND (?=FALSE OR DaDoc=0) ORDER BY NgayTao DESC LIMIT ?"
            + " OFFSET ?",
        a.getName(),
        unread,
        size,
        Db.offset(page, size));
  }

  /** Đánh dấu đã đọc có ownership, không sửa nội dung thông báo. */
  @PostMapping("/notifications/{id}/read")
  public Map<String, Object> read(Authentication a, @PathVariable String id) {
    db.one("SELECT MaThongBao FROM ThongBao WHERE MaThongBao=? AND MaNguoiDung=?", id, a.getName());
    db.exec("UPDATE ThongBao SET DaDoc=1 WHERE MaThongBao=?", id);
    return Map.of("read", true);
  }
}
