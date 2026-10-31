package com.yofaso.shop.order;

import com.yofaso.shop.common.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đặt hàng, snapshot giá, voucher và chuyển trạng thái có khóa chống bán lố. */
@Service
public class OrderService {
  private final Db db;
  private final InventoryService stock;
  private final Idempotency keys;
  public static final String SUMMARY =
      "SELECT MaDonHang AS orderId,MaNguoiDung AS userId,KenhBanHang AS channel,TenNguoiNhan AS"
          + " recipientName,SoDienThoaiNhan AS phone,DiaChiGiaoChiTiet AS address,TrangThaiDonHang"
          + " AS status,TrangThaiThanhToan AS paymentStatus,PhuongThucThanhToan AS"
          + " paymentMethod,TienGiamGia AS discount,PhiVanChuyen AS shippingFee,SoTienThuHo AS"
          + " collectAmount,MaNguoiGiaoHang AS shipperId,NgayTao AS"
          + " createdAt FROM DonHang";

  /** Nhận kho và idempotency trong cùng transaction MySQL. */
  public OrderService(Db db, InventoryService stock, Idempotency keys) {
    this.db = db;
    this.stock = stock;
    this.keys = keys;
  }

  /** Trả đơn cùng tổng giá tính từ snapshot dòng hàng. */
  public Map<String, Object> detail(String id) {
    var row = db.one(SUMMARY + " WHERE MaDonHang=?", id);
    var lines =
        db.rows(
            "SELECT MaChiTietDonHang AS orderLineId,MaBienThe AS variantId,TenSanPhamLucDatHang AS"
                + " productName,TenMauLucDatHang AS colorName,KichCoLucDatHang AS size,SoLuong AS"
                + " quantity,DonGiaLucDatHang AS unitPrice,MaPhienThuDo AS sessionId FROM"
                + " ChiTietDonHang WHERE MaDonHang=?",
            id);
    BigDecimal subtotal =
        lines.stream()
            .map(
                l ->
                    Db.money(l, "unitPrice").multiply(new BigDecimal(l.get("quantity").toString())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    row.put("items", lines);
    row.put("subtotal", subtotal);
    row.put(
        "total", subtotal.subtract(Db.money(row, "discount")).add(Db.money(row, "shippingFee")));
    return row;
  }

  /** Kiểm tra ownership hoặc vai trò staff ở caller, tránh IDOR. */
  public void own(String id, String owner) {
    db.one("SELECT MaDonHang FROM DonHang WHERE MaDonHang=? AND MaNguoiDung=?", id, owner);
  }

  /** Tạo online/POS; không nhận đơn giá, phí ship hay tổng tiền do client tính. */
  @Transactional
  public Map<String, Object> create(
      String actor, String key, OrderController.Checkout b, boolean pos) {
    String scope = pos ? "POS_ORDER" : "ORDER";
    String old = keys.existing(actor, scope, key, b);
    if (old != null) return detail(old);
    String customer = pos ? b.customerId() : actor;
    Map<String, Object> address = null;
    if (!pos)
      address =
          db.one("SELECT * FROM SoDiaChi WHERE MaDiaChi=? AND MaNguoiDung=?", b.addressId(), actor);
    if (customer != null)
      db.one(
          "SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? AND TrangThaiHoatDong='HOAT_DONG'",
          customer);
    String shift = null;
    if (pos) {
      var s =
          db.one(
              "SELECT MaCaLamViec FROM CaLamViecThuNgan WHERE MaThuNgan=? AND TrangThaiCa='DANG_MO'"
                  + " FOR UPDATE",
              actor);
      shift = Db.text(s, "MaCaLamViec");
    }
    var items = new TreeMap<String, Integer>();
    for (var item : b.items()) {
      Problem.require(
          !items.containsKey(item.variantId()), 422, "DUPLICATE_VARIANT", "SKU bị lặp trong đơn.");
      items.put(item.variantId(), item.quantity());
    }
    var snapshots = new ArrayList<Map<String, Object>>();
    BigDecimal subtotal = BigDecimal.ZERO;
    for (var e : items.entrySet()) {
      var v =
          db.one(
              "SELECT b.*,p.TenSanPham,p.TrangThaiSanPham FROM BienTheSanPham b JOIN SanPham p ON"
                  + " p.MaSanPham=b.MaSanPham WHERE b.MaBienThe=? FOR UPDATE",
              e.getKey());
      Problem.require(
          "DANG_KINH_DOANH".equals(v.get("TrangThaiSanPham"))
              && "DANG_BAN".equals(v.get("TrangThai")),
          409,
          "PRODUCT_UNAVAILABLE",
          "Sản phẩm/SKU đã ngừng bán.");
      var price =
          v.get("GiaKhuyenMai") == null ? Db.money(v, "GiaNiemYet") : Db.money(v, "GiaKhuyenMai");
      v.put("quantity", e.getValue());
      v.put("unitPrice", price);
      snapshots.add(v);
      subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(e.getValue())));
    }
    Map<String, Object> promo = discount(b.voucher(), subtotal, true);
    BigDecimal discount = (BigDecimal) promo.get("discount");
    BigDecimal total = subtotal.subtract(discount);
    String id = keys.resourceId(actor, scope, key, "DH"), method = pos ? "TIEN_MAT_TAI_QUAY" : "COD_TIEN_MAT_KHI_NHAN";
    if (pos)
      Problem.require(
          b.cashReceived() != null && b.cashReceived().compareTo(total) >= 0,
          422,
          "CASH_INSUFFICIENT",
          "Số tiền khách đưa chưa đủ.");
    String name =
        pos
            ? (customer == null
                ? "Khách tại quầy"
                : Db.text(
                    db.one("SELECT HoVaTen FROM NguoiDung WHERE MaNguoiDung=?", customer),
                    "HoVaTen"))
            : Db.text(address, "TenNguoiNhan");
    String phone = pos ? "0000000000" : Db.text(address, "SoDienThoaiNhan");
    String addressText =
        pos
            ? null
            : Db.text(address, "SoNhaTenDuong")
                + ", "
                + Db.text(address, "PhuongXa")
                + ", "
                + Db.text(address, "TinhThanhPho");
    Problem.require(addressText == null || addressText.length() <= 255, 422, "ADDRESS_TOO_LONG",
        "Địa chỉ giao hàng vượt 255 ký tự; cập nhật sổ địa chỉ trước khi đặt hàng.");
    db.exec(
        "INSERT INTO"
            + " DonHang(MaDonHang,MaNguoiDung,KenhBanHang,MaThuNgan,MaDiaChiGiaoHang,TenNguoiNhan,SoDienThoaiNhan,DiaChiGiaoChiTiet,TienGiamGia,PhiVanChuyen,PhuongThucThanhToan,TrangThaiThanhToan,TrangThaiDonHang,SoTienThuHo,MaKhuyenMai)"
            + " VALUES(?,?,?,?,?,?,?,?,?,0,?,?,?,?,?)",
        id,
        customer,
        pos ? "TAI_QUAY_POS" : "ONLINE_APP",
        pos ? actor : null,
        pos ? null : b.addressId(),
        name,
        phone,
        addressText,
        discount,
        method,
        pos ? "DA_THANH_TOAN" : "CHUA_THANH_TOAN",
        pos ? "HOAN_TAT" : "CHO_XAC_NHAN",
        pos ? BigDecimal.ZERO : total,
        promo.get("promotionId"));
    if (pos)
      db.exec("UPDATE DonHang SET ThoiGianThanhToan=CURRENT_TIMESTAMP WHERE MaDonHang=?", id);
    for (var v : snapshots) {
      String variant = Db.text(v, "MaBienThe");
      int quantity = (int) v.get("quantity");
      stock.move(
          variant,
          -quantity,
          pos ? "BAN_TAI_QUAY" : "GIU_HANG_ONLINE",
          id,
          actor,
          "Giữ tồn khi tạo đơn");
      db.exec(
          "INSERT INTO"
              + " ChiTietDonHang(MaChiTietDonHang,MaDonHang,MaBienThe,TenSanPhamLucDatHang,TenMauLucDatHang,KichCoLucDatHang,SoLuong,DonGiaLucDatHang)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          Db.id("CTDH"),
          id,
          variant,
          v.get("TenSanPham"),
          v.get("TenMau"),
          v.get("KichCo"),
          quantity,
          v.get("unitPrice"));
    }
    if (!pos)
      db.exec(
          "DELETE FROM ChiTietGioHang WHERE MaGioHang IN (SELECT MaGioHang FROM GioHang WHERE"
              + " MaNguoiDung=?) AND MaBienThe IN (SELECT MaBienThe FROM ChiTietDonHang WHERE"
              + " MaDonHang=?)",
          actor,
          id);
    keys.complete(actor, scope, key, id);
    db.audit(actor, "ORDER_CREATE", id);
    if (customer != null) notice(customer, id, "Đơn hàng đã được tạo.");
    return detail(id);
  }

  /** Tính voucher theo giá server; khi consume khóa voucher để không dùng quá số lượng. */
  public Map<String, Object> discount(String code, BigDecimal subtotal, boolean consume) {
    Map<String, Object> result = new HashMap<>();
    result.put("discount", BigDecimal.ZERO);
    result.put("promotionId", null);
    if (code == null || code.isBlank()) return result;
    var p =
        db.one(
            "SELECT * FROM KhuyenMai WHERE MaPhieuGiamGia=? AND TrangThai='DANG_DIEN_RA' AND"
                + " NgayBatDau<=CURRENT_TIMESTAMP AND NgayKetThuc>=CURRENT_TIMESTAMP"
                + (consume ? " FOR UPDATE" : ""),
            code);
    Problem.require(
        ((Number) p.get("SoLuongDaDung")).intValue()
                < ((Number) p.get("SoLuongPhatHanh")).intValue()
            && subtotal.compareTo(Db.money(p, "GiaTriDonHangToiThieu")) >= 0,
        409,
        "VOUCHER_UNAVAILABLE",
        "Voucher hết lượt hoặc chưa đạt đơn tối thiểu.");
    BigDecimal value = Db.money(p, "GiaTriGiam");
    BigDecimal amount =
        switch (Db.text(p, "LoaiGiamGia")) {
          case "PHAN_TRAM" ->
              subtotal.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
          case "TIEN_MAT_TRUC_TIEP" -> value;
          default -> BigDecimal.ZERO;
        };
    if (p.get("GiamToiDa") != null) amount = amount.min(Db.money(p, "GiamToiDa"));
    result.put("discount", amount.min(subtotal));
    result.put("promotionId", p.get("MaKhuyenMai"));
    if (consume)
      db.exec(
          "UPDATE KhuyenMai SET SoLuongDaDung=SoLuongDaDung+1 WHERE MaKhuyenMai=?",
          p.get("MaKhuyenMai"));
    return result;
  }

  /** Hủy/nhận hoàn chỉ hoàn tồn một lần vì trạng thái đơn được khóa. */
  @Transactional
  public Map<String, Object> cancel(String id, String actor, boolean customer, boolean returned) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    String state = Db.text(o, "TrangThaiDonHang");
    String target = returned ? "TRA_HANG" : "DA_HUY";
    if (customer)
      Problem.require(actor.equals(o.get("MaNguoiDung")), 404, "NOT_FOUND", "Không có đơn hàng.");
    if (state.equals(target)) return detail(id);
    Problem.require(
        returned
            ? Set.of("DANG_GIAO_HANG", "HOAN_TAT").contains(state)
            : (customer
                ? state.equals("CHO_XAC_NHAN")
                : Set.of("CHO_XAC_NHAN", "DANG_DONG_GOI").contains(state)),
        409,
        "ORDER_STATE_INVALID",
        "Không thể hủy/nhận hoàn ở trạng thái này.");
    for (var line :
        db.rows(
            "SELECT MaBienThe,SoLuong FROM ChiTietDonHang WHERE MaDonHang=? ORDER BY MaBienThe",
            id))
      stock.move(
          Db.text(line, "MaBienThe"),
          ((Number) line.get("SoLuong")).intValue(),
          "NHAP_KHO_HANG_HOAN",
          id,
          actor,
          returned ? "Đã nhận hàng hoàn vật lý" : "Hủy giữ tồn");
    db.exec("UPDATE DonHang SET TrangThaiDonHang=?,SoTienThuHo=0 WHERE MaDonHang=?", target, id);
    if (o.get("MaKhuyenMai") != null)
      db.exec(
          "UPDATE KhuyenMai SET SoLuongDaDung=GREATEST(0,SoLuongDaDung-1) WHERE MaKhuyenMai=?",
          o.get("MaKhuyenMai"));
    // Hoàn hàng đã thanh toán vẫn cần đối soát tiền; không tự ghi DA_HOAN_TIEN.
    db.audit(actor, returned ? "ORDER_RETURN_RECEIVED" : "ORDER_CANCEL", id);
    return detail(id);
  }

  /** Đóng gói/giao hàng do từng endpoint staff kiểm tra quyền và assigned shipper. */
  @Transactional
  public Map<String, Object> transition(String id, String actor, String action, String shipper) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    String state = Db.text(o, "TrangThaiDonHang");
    switch (action) {
      case "pack" -> {
        Problem.require(
            state.equals("CHO_XAC_NHAN"),
            409,
            "ORDER_STATE_INVALID",
            "Đơn chưa ở trạng thái chờ xác nhận.");
        db.exec("UPDATE DonHang SET TrangThaiDonHang='DANG_DONG_GOI' WHERE MaDonHang=?", id);
      }
      case "assign" -> {
        Problem.require(
            state.equals("DANG_DONG_GOI"), 409, "ORDER_STATE_INVALID", "Đơn phải đang đóng gói.");
        Problem.require(
            db.count(
                    "SELECT COUNT(*) FROM NguoiDung n JOIN NguoiDung_VaiTro r ON"
                        + " r.MaNguoiDung=n.MaNguoiDung JOIN VaiTro v ON v.MaVaiTro=r.MaVaiTro WHERE n.MaNguoiDung=? AND"
                        + " v.TenVaiTro='ROLE_STAFF_SHIPPER' AND n.TrangThaiHoatDong='HOAT_DONG'",
                    shipper)
                > 0,
            422,
            "SHIPPER_INVALID",
            "Nhân viên không có quyền giao hàng.");
        db.exec(
            "UPDATE DonHang SET"
                + " MaNguoiGiaoHang=?,TrangThaiDonHang='DANG_GIAO_HANG',SoLanGiao=SoLanGiao+1 WHERE"
                + " MaDonHang=?",
            shipper,
            id);
      }
      case "deliver" -> {
        Problem.require(
            actor.equals(o.get("MaNguoiGiaoHang")) && state.equals("DANG_GIAO_HANG"),
            409,
            "ORDER_STATE_INVALID",
            "Không phải đơn bạn đang giao.");
        db.exec(
            "UPDATE DonHang SET"
                + " TrangThaiDonHang='HOAN_TAT',TrangThaiThanhToan='DA_THANH_TOAN',ThoiGianThanhToan=CURRENT_TIMESTAMP,SoTienThuHo=0"
                + " WHERE MaDonHang=?",
            id);
      }
      case "failed" -> {
        Problem.require(
            actor.equals(o.get("MaNguoiGiaoHang")) && state.equals("DANG_GIAO_HANG"),
            409,
            "ORDER_STATE_INVALID",
            "Không phải đơn bạn đang giao.");
        db.exec(
            "UPDATE DonHang SET TrangThaiDonHang='DANG_DONG_GOI',MaNguoiGiaoHang=NULL WHERE"
                + " MaDonHang=?",
            id);
      }
      default -> throw new Problem(400, "ACTION_INVALID", "Thao tác đơn không hợp lệ.");
    }
    db.audit(actor, "ORDER_" + action.toUpperCase(Locale.ROOT), id);
    if (o.get("MaNguoiDung") != null)
      notice(Db.text(o, "MaNguoiDung"), id, "Đơn đã cập nhật trạng thái.");
    return detail(id);
  }

  /** Thông báo gọn, không nhét địa chỉ/số điện thoại vào log. */
  private void notice(String user, String id, String message) {
    db.exec(
        "INSERT INTO ThongBao(MaThongBao,MaNguoiDung,TieuDe,NoiDung,LoaiThongBao,LienKetDieuHuong)"
            + " VALUES(?,?,?,?,'DON_HANG',?)",
        Db.id("TB"),
        user,
        "Cập nhật đơn hàng",
        message,
        "/orders/" + id);
  }
}
