package com.yofaso.shop.order;

import com.yofaso.shop.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Giỏ hàng và đơn riêng của khách; API staff tách khỏi API khách. */
@RestController
@RequestMapping("/api/v1")
public class OrderController {
  public record Item(@NotBlank @Size(max = 20) String variantId, @Min(1) @Max(99) int quantity) {}

  public record Checkout(
      @Size(max = 20) String addressId,
      @NotEmpty @Size(max = 50) List<@Valid Item> items,
      @Size(max = 50) String voucher,
      @Size(max = 20) String customerId,
      @DecimalMin("0") BigDecimal cashReceived) {}

  private final Db db;
  private final OrderService orders;

  /** Nhận service đặt hàng và repository giỏ. */
  public OrderController(Db db, OrderService orders) {
    this.db = db;
    this.orders = orders;
  }

  /** Giỏ cá nhân luôn dùng giá hiện tại, không cam kết giá đến khi checkout. */
  @GetMapping("/cart")
  public List<Map<String, Object>> cart(Authentication a) {
    return db.rows(
        "SELECT c.MaBienThe AS variantId,c.SoLuong AS quantity,b.MaSanPham AS productId,b.TenMau AS"
            + " colorName,b.KichCo AS size,COALESCE(b.GiaKhuyenMai,b.GiaNiemYet) AS"
            + " unitPrice,b.SoLuongTonKho AS stock FROM ChiTietGioHang c JOIN GioHang g ON"
            + " g.MaGioHang=c.MaGioHang JOIN BienTheSanPham b ON b.MaBienThe=c.MaBienThe WHERE"
            + " g.MaNguoiDung=?",
        a.getName());
  }

  /** Đặt số lượng tuyệt đối để retry PUT không cộng thêm hàng. */
  @PutMapping("/cart/items")
  @Transactional
  public List<Map<String, Object>> cartItem(Authentication a, @Valid @RequestBody Item b) {
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", a.getName());
    var v =
        db.one(
            "SELECT b.SoLuongTonKho,b.TrangThai,p.TrangThaiSanPham FROM BienTheSanPham b JOIN"
                + " SanPham p ON p.MaSanPham=b.MaSanPham WHERE b.MaBienThe=?",
            b.variantId());
    Problem.require(
        "DANG_BAN".equals(v.get("TrangThai"))
            && "DANG_KINH_DOANH".equals(v.get("TrangThaiSanPham"))
            && ((Number) v.get("SoLuongTonKho")).intValue() >= b.quantity(),
        409,
        "PRODUCT_UNAVAILABLE",
        "SKU ngừng bán hoặc không đủ tồn.");
    var g = db.rows("SELECT MaGioHang FROM GioHang WHERE MaNguoiDung=?", a.getName());
    String gid = g.isEmpty() ? Db.id("GH") : Db.text(g.getFirst(), "MaGioHang");
    if (g.isEmpty())
      db.exec("INSERT INTO GioHang(MaGioHang,MaNguoiDung) VALUES(?,?)", gid, a.getName());
    db.exec("DELETE FROM ChiTietGioHang WHERE MaGioHang=? AND MaBienThe=?", gid, b.variantId());
    db.exec(
        "INSERT INTO ChiTietGioHang(MaChiTietGioHang,MaGioHang,MaBienThe,SoLuong) VALUES(?,?,?,?)",
        Db.id("CTGH"),
        gid,
        b.variantId(),
        b.quantity());
    return cart(a);
  }

  /** Xóa SKU khỏi giỏ của chính mình. */
  @DeleteMapping("/cart/items/{id}")
  public Map<String, Object> remove(Authentication a, @PathVariable String id) {
    db.exec(
        "DELETE FROM ChiTietGioHang WHERE MaBienThe=? AND MaGioHang IN (SELECT MaGioHang FROM"
            + " GioHang WHERE MaNguoiDung=?)",
        id,
        a.getName());
    return Map.of("deleted", true);
  }

  /** Checkout COD, key chống tạo hai đơn khi mạng chập chờn. */
  @PostMapping("/orders")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> create(
      Authentication a,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Checkout b) {
    Problem.require(
        b.addressId() != null && b.customerId() == null && b.cashReceived() == null,
        422,
        "CHECKOUT_INVALID",
        "Đơn online cần addressId và không có field POS.");
    return orders.create(a.getName(), key, b, false);
  }

  /** Lịch sử đơn riêng, không nhận userId để xem người khác. */
  @GetMapping("/orders")
  public List<Map<String, Object>> list(
      Authentication a,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return db.rows(
        OrderService.SUMMARY
            + " WHERE MaNguoiDung=? ORDER BY NgayTao DESC,MaDonHang LIMIT ? OFFSET ?",
        a.getName(),
        size,
        Db.offset(page, size));
  }

  /** Chi tiết đơn được kiểm tra ownership. */
  @GetMapping("/orders/{id}")
  public Map<String, Object> detail(Authentication a, @PathVariable String id) {
    orders.own(id, a.getName());
    return orders.detail(id);
  }

  /** Khách chỉ hủy trước khi đóng gói, hoàn tồn nguyên tử. */
  @PostMapping("/orders/{id}/cancel")
  public Map<String, Object> cancel(Authentication a, @PathVariable String id) {
    return orders.cancel(id, a.getName(), true, false);
  }

  /** Voucher đang hiệu lực, không lộ dữ liệu tài chính nội bộ. */
  @GetMapping("/promotions")
  public List<Map<String, Object>> promotions() {
    return db.rows(
        "SELECT MaPhieuGiamGia AS code,TenChuongTrinh AS name,LoaiGiamGia AS type,GiaTriGiam AS"
            + " value,GiaTriDonHangToiThieu AS minimumTotal,GiamToiDa AS maxDiscount,NgayKetThuc AS"
            + " expiresAt FROM KhuyenMai WHERE TrangThai='DANG_DIEN_RA' AND"
            + " NgayBatDau<=CURRENT_TIMESTAMP AND NgayKetThuc>=CURRENT_TIMESTAMP AND"
            + " SoLuongDaDung<SoLuongPhatHanh");
  }

  /** Xem giảm giá theo giá server của SKU, chưa tiêu lượt voucher. */
  @PostMapping("/promotions/quote")
  public Map<String, Object> quote(@Valid @RequestBody Checkout b) {
    BigDecimal subtotal = BigDecimal.ZERO;
    for (var item : b.items()) {
      var v =
          db.one(
              "SELECT COALESCE(GiaKhuyenMai,GiaNiemYet) AS price FROM BienTheSanPham WHERE"
                  + " MaBienThe=?",
              item.variantId());
      subtotal = subtotal.add(Db.money(v, "price").multiply(BigDecimal.valueOf(item.quantity())));
    }
    return orders.discount(b.voucher(), subtotal, false);
  }
}
