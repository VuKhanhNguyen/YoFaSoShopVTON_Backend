package com.yofaso.shop.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Idempotency;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.notification.NotificationService;

/** Đặt hàng, snapshot giá, voucher và chuyển trạng thái có khóa chống bán lố. */
@Service
public class OrderService {
  private final Db db;
  private final InventoryService stock;
  private final Idempotency keys;
  private final NotificationService notifications;
  public static final String SUMMARY =
      "SELECT MaDonHang AS orderId,MaNguoiDung AS userId,KenhBanHang AS channel,TenNguoiNhan AS"
          + " recipientName,SoDienThoaiNhan AS phone,DiaChiGiaoChiTiet AS address,TrangThaiDonHang"
          + " AS status,TrangThaiThanhToan AS paymentStatus,PhuongThucThanhToan AS"
          + " paymentMethod,(PhuongThucThanhToan='VIETQR_CHUYEN_KHOAN_ONLINE'"
          + " AND TrangThaiThanhToan='CHUA_THANH_TOAN'"
          + " AND NgayTao <= CURRENT_TIMESTAMP - INTERVAL 15 MINUTE) AS paymentExpired,"
          + " TienGiamGia AS discount,PhiVanChuyen AS shippingFee,SoTienThuHo AS"
          + " collectAmount,MaNguoiGiaoHang AS shipperId,MaKhuyenMai AS promotionId,GhiChuDonHang AS orderNote,"
          + " (TrangThaiDonHang='TRA_HANG' AND NOT EXISTS (SELECT 1 FROM BienDongKho b"
          + " WHERE b.MaThamChieu=DonHang.MaDonHang AND (b.LoaiBienDong='NHAP_KHO_HANG_HOAN'"
          + " OR b.GhiChu='Hàng hoàn lỗi - chuyển kho chờ thanh lý')"
          + " AND COALESCE(b.GhiChu,'') <> 'Hủy giữ tồn')) AS returnPending,"
          + " (SELECT MaPhieuGiamGia FROM KhuyenMai WHERE MaKhuyenMai=DonHang.MaKhuyenMai) AS voucherCode,NgayTao AS createdAt,"
          + " (SELECT COALESCE(SUM(c.SoLuong * c.DonGiaLucDatHang), 0) FROM ChiTietDonHang c WHERE c.MaDonHang=DonHang.MaDonHang) AS subtotal"
          + " FROM DonHang";

  /** Tương thích unit test không dùng NotificationService. */
  public OrderService(Db db, InventoryService stock, Idempotency keys) {
    this(db, stock, keys, null);
  }

  /** Nhận kho, idempotency và dịch vụ thông báo trong cùng transaction MySQL. */
  @Autowired
  public OrderService(
      Db db, InventoryService stock, Idempotency keys, NotificationService notifications) {
    this.db = db;
    this.stock = stock;
    this.keys = keys;
    this.notifications = notifications;
  }

  /** Trả đơn cùng tổng giá tính từ snapshot dòng hàng và ảnh đại diện. */
  public Map<String, Object> detail(String id) {
    var row = db.one(SUMMARY + " WHERE MaDonHang=?", id);
    var lines =
        db.rows(
            "SELECT c.MaChiTietDonHang AS orderLineId,c.MaBienThe AS variantId,c.TenSanPhamLucDatHang AS"
                + " productName,c.TenMauLucDatHang AS colorName,c.KichCoLucDatHang AS size,c.SoLuong AS"
                + " quantity,c.DonGiaLucDatHang AS unitPrice,c.MaPhienThuDo AS sessionId,"
                + "(SELECT dg.MaDanhGia FROM DanhGiaSanPham dg WHERE dg.MaChiTietDonHang = c.MaChiTietDonHang LIMIT 1) AS reviewId,"
                + "(SELECT dg.TrangThaiDuyet FROM DanhGiaSanPham dg WHERE dg.MaChiTietDonHang = c.MaChiTietDonHang LIMIT 1) AS reviewStatus,"
                + "(SELECT h.MaHinhAnh FROM HinhAnhSanPham h JOIN BienTheSanPham b ON b.MaSanPham = h.MaSanPham WHERE b.MaBienThe = c.MaBienThe AND (h.MaMauSac = b.MaMauSac OR h.LaAnhDaiDien = 1) ORDER BY (h.MaMauSac = b.MaMauSac) DESC, h.LaAnhDaiDien DESC, h.ThuTuHienThi ASC LIMIT 1) AS imageId FROM"
                + " ChiTietDonHang c WHERE c.MaDonHang=?",
            id);
    BigDecimal subtotal = BigDecimal.ZERO;
    for (var l : lines) {
      if (l.get("imageId") != null) {
        l.put("coverUrl", "/api/v1/catalog/images/" + l.get("imageId") + "/content?kind=original");
      }
      BigDecimal p = Db.money(l, "unitPrice");
      int q = ((Number) l.get("quantity")).intValue();
      subtotal = subtotal.add(p.multiply(BigDecimal.valueOf(q)));
    }
    row.put("items", lines);
    row.put("subtotal", subtotal);
    row.put(
        "total", subtotal.subtract(Db.money(row, "discount")).add(Db.money(row, "shippingFee")));
    return row;
  }

  /** Lịch sử đơn của khách kèm snapshot dòng hàng, ảnh sản phẩm và tổng tiền tính từ DB. */
  public List<Map<String, Object>> listForUser(String userId, int page, int size) {
    var rows =
        db.rows(
            SUMMARY
                + " WHERE MaNguoiDung=? ORDER BY NgayTao DESC,MaDonHang LIMIT ? OFFSET ?",
            userId,
            size,
            Db.offset(page, size));
    for (var r : rows) {
      String id = Db.text(r, "orderId");
      var lines =
          db.rows(
              "SELECT c.MaChiTietDonHang AS orderLineId,c.MaBienThe AS variantId,c.TenSanPhamLucDatHang AS"
                  + " productName,c.TenMauLucDatHang AS colorName,c.KichCoLucDatHang AS size,c.SoLuong AS"
                  + " quantity,c.DonGiaLucDatHang AS unitPrice,c.MaPhienThuDo AS sessionId,"
                  + "(SELECT dg.MaDanhGia FROM DanhGiaSanPham dg WHERE dg.MaChiTietDonHang = c.MaChiTietDonHang LIMIT 1) AS reviewId,"
                  + "(SELECT dg.TrangThaiDuyet FROM DanhGiaSanPham dg WHERE dg.MaChiTietDonHang = c.MaChiTietDonHang LIMIT 1) AS reviewStatus,"
                  + "(SELECT h.MaHinhAnh FROM HinhAnhSanPham h JOIN BienTheSanPham b ON b.MaSanPham = h.MaSanPham WHERE b.MaBienThe = c.MaBienThe AND (h.MaMauSac = b.MaMauSac OR h.LaAnhDaiDien = 1) ORDER BY (h.MaMauSac = b.MaMauSac) DESC, h.LaAnhDaiDien DESC, h.ThuTuHienThi ASC LIMIT 1) AS imageId FROM"
                  + " ChiTietDonHang c WHERE c.MaDonHang=?",
              id);
      BigDecimal subtotal = BigDecimal.ZERO;
      for (var l : lines) {
        if (l.get("imageId") != null) {
          l.put("coverUrl", "/api/v1/catalog/images/" + l.get("imageId") + "/content?kind=original");
        }
        BigDecimal p = Db.money(l, "unitPrice");
        int q = ((Number) l.get("quantity")).intValue();
        subtotal = subtotal.add(p.multiply(BigDecimal.valueOf(q)));
      }
      r.put("items", lines);
      r.put("subtotal", subtotal);
      r.put(
          "total", subtotal.subtract(Db.money(r, "discount")).add(Db.money(r, "shippingFee")));
    }
    return rows;
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
    var itemSessionHints = new HashMap<String, String>();
    for (var item : b.items()) {
      Problem.require(
          !items.containsKey(item.variantId()), 422, "DUPLICATE_VARIANT", "SKU bị lặp trong đơn.");
      items.put(item.variantId(), item.quantity());
      if (item.sessionId() != null && !item.sessionId().isBlank()) {
        itemSessionHints.put(item.variantId(), item.sessionId().trim());
      }
    }
    var snapshots = new ArrayList<Map<String, Object>>();
    var priceByVariant = new HashMap<String, BigDecimal>();
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
      priceByVariant.put(e.getKey(), price);
      subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(e.getValue())));
    }
    var quote = computeQuote(customer, items, priceByVariant, itemSessionHints, b.voucher(), b.sessionId(), pos, true);
    BigDecimal comboDiscount = quote.comboDiscount();
    BigDecimal discount = quote.totalDiscount();
    BigDecimal shippingFee = quote.shippingFee();
    BigDecimal total = quote.total();
    String id = keys.resourceId(actor, scope, key, "DH");
    String method = pos ? "TIEN_MAT_TAI_QUAY" : "COD_TIEN_MAT_KHI_NHAN";
    boolean isVietQr = false;
    if (!pos && b.paymentMethod() != null) {
      if ("VIETQR".equalsIgnoreCase(b.paymentMethod())
          || "VIETQR_CHUYEN_KHOAN_ONLINE".equalsIgnoreCase(b.paymentMethod())) {
        method = "VIETQR_CHUYEN_KHOAN_ONLINE";
        isVietQr = true;
      }
    }
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
    String orderNote = null;
    if (comboDiscount.compareTo(BigDecimal.ZERO) > 0) {
      orderNote = "Ưu đãi Combo VTON -5%: -" + comboDiscount.setScale(0, RoundingMode.HALF_UP) + "đ";
      if (quote.voucherCode() != null && quote.voucherDiscount().compareTo(BigDecimal.ZERO) > 0) {
        orderNote += " | Voucher " + quote.voucherCode() + ": -" + quote.voucherDiscount().setScale(0, RoundingMode.HALF_UP) + "đ";
      }
    }
    db.exec(
        "INSERT INTO"
            + " DonHang(MaDonHang,MaNguoiDung,KenhBanHang,MaThuNgan,MaDiaChiGiaoHang,TenNguoiNhan,SoDienThoaiNhan,DiaChiGiaoChiTiet,TienGiamGia,PhiVanChuyen,PhuongThucThanhToan,TrangThaiThanhToan,TrangThaiDonHang,SoTienThuHo,MaKhuyenMai,GhiChuDonHang)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        customer,
        pos ? "TAI_QUAY_POS" : "ONLINE_APP",
        pos ? actor : null,
        pos ? null : b.addressId(),
        name,
        phone,
        addressText,
        discount,
        shippingFee,
        method,
        pos ? "DA_THANH_TOAN" : "CHUA_THANH_TOAN",
        pos ? "HOAN_TAT" : "CHO_XAC_NHAN",
        (pos || isVietQr) ? BigDecimal.ZERO : total,
        quote.promotionId(),
        orderNote);
    if (quote.promotionId() != null && customer != null) {
      try {
        db.exec(
            "INSERT INTO NguoiDung_KhuyenMai (MaNguoiDung, MaKhuyenMai, NgayThuThap, TrangThai, NgaySuDung, MaDonHangDaDung) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?) "
                + "ON DUPLICATE KEY UPDATE TrangThai=VALUES(TrangThai), NgaySuDung=CURRENT_TIMESTAMP, MaDonHangDaDung=VALUES(MaDonHangDaDung)",
            customer, quote.promotionId(), pos ? "DA_DUNG" : "CHUA_DUNG", id);
      } catch (Exception ignored) {}
    }
    if (pos)
      db.exec("UPDATE DonHang SET ThoiGianThanhToan=CURRENT_TIMESTAMP WHERE MaDonHang=?", id);
    for (var v : snapshots) {
      String variant = Db.text(v, "MaBienThe");
      int quantity = (int) v.get("quantity");
      String lineSessionId = quote.variantSessionMap().get(variant);
      stock.move(
          variant,
          -quantity,
          pos ? "BAN_TAI_QUAY" : "GIU_HANG_ONLINE",
          id,
          actor,
          "Giữ tồn khi tạo đơn");
      db.exec(
          "INSERT INTO"
              + " ChiTietDonHang(MaChiTietDonHang,MaDonHang,MaBienThe,TenSanPhamLucDatHang,TenMauLucDatHang,KichCoLucDatHang,SoLuong,DonGiaLucDatHang,MaPhienThuDo)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          Db.id("CTDH"),
          id,
          variant,
          v.get("TenSanPham"),
          v.get("TenMau"),
          v.get("KichCo"),
          quantity,
          v.get("unitPrice"),
          lineSessionId);
    }
    if (!pos)
      db.exec(
          "DELETE FROM ChiTietGioHang WHERE MaGioHang IN (SELECT MaGioHang FROM GioHang WHERE"
              + " MaNguoiDung=?) AND MaBienThe IN (SELECT MaBienThe FROM ChiTietDonHang WHERE"
              + " MaDonHang=?)",
          actor,
          id);
    if (pos) {
      markSuggestionsPurchased(customer, id, b.sessionId());
      awardOrderLoyalty(customer, id);
    }
    keys.complete(actor, scope, key, id);
    db.audit(actor, "ORDER_CREATE", id);
    if (customer != null) notice(customer, id, "Đơn hàng #" + id + " đã được tạo thành công.");
    if (!pos) {
      notifyRole(
          "ROLE_STAFF_WAREHOUSE",
          "Đơn hàng online mới #" + id,
          "Khách " + name + " vừa đặt đơn hàng mới (" + snapshots.size() + " dòng hàng). Vui lòng tiếp nhận đóng gói.",
          "DON_HANG",
          "/warehouse/fulfillment?tab=pick");
      notifyRole(
          "ROLE_SHOP_ADMIN",
          "Đơn hàng mới #" + id,
          "Đơn hàng online #" + id + " từ " + name + " (" + total.setScale(0, RoundingMode.HALF_UP) + "đ).",
          "DON_HANG",
          "/admin/dashboard?tab=operations&order=" + id);
    }
    return detail(id);
  }

  private static final BigDecimal FREE_SHIP_THRESHOLD = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal DEFAULT_SHIPPING_FEE = BigDecimal.valueOf(30_000);

  private record QuoteInternal(
      BigDecimal subtotal,
      BigDecimal comboDiscount,
      List<String> comboVariantIds,
      String comboSessionId,
      BigDecimal voucherDiscount,
      String voucherCode,
      String voucherName,
      String voucherType,
      String promotionId,
      BigDecimal totalDiscount,
      BigDecimal shippingFee,
      String shippingFreeReason,
      BigDecimal total,
      List<Map<String, Object>> discountDetails,
      Map<String, String> variantSessionMap,
      String voucherWarning) {}

  /** Báo giá chính thức phía server: Combo VTON -5% trước, Voucher trên (subtotal - comboDiscount), thống nhất phí ship. */
  public Map<String, Object> quoteOrder(
      String customerId,
      List<OrderController.Item> rawItems,
      String voucherCode,
      String requestSessionId,
      boolean pos,
      boolean consume) {
    var items = new TreeMap<String, Integer>();
    var hints = new HashMap<String, String>();
    for (var item : rawItems) {
      items.merge(item.variantId(), item.quantity(), Integer::sum);
      if (item.sessionId() != null && !item.sessionId().isBlank()) {
        hints.put(item.variantId(), item.sessionId().trim());
      }
    }
    var prices = new HashMap<String, BigDecimal>();
    for (String vid : items.keySet()) {
      var v = db.one("SELECT COALESCE(GiaKhuyenMai,GiaNiemYet) AS price FROM BienTheSanPham WHERE MaBienThe=?", vid);
      prices.put(vid, Db.money(v, "price"));
    }
    var q = computeQuote(customerId, items, prices, hints, voucherCode, requestSessionId, pos, consume);
    var res = new LinkedHashMap<String, Object>();
    res.put("subtotal", q.subtotal());
    res.put("comboEligible", q.comboDiscount().compareTo(BigDecimal.ZERO) > 0);
    res.put("comboDiscount", q.comboDiscount());
    res.put("comboVariantIds", q.comboVariantIds());
    res.put("comboSessionId", q.comboSessionId());
    res.put("voucherDiscount", q.voucherDiscount());
    res.put("voucherCode", q.voucherCode());
    res.put("voucherName", q.voucherName());
    res.put("voucherType", q.voucherType());
    res.put("promotionId", q.promotionId());
    res.put("discount", q.totalDiscount());
    res.put("totalDiscount", q.totalDiscount());
    res.put("shippingFee", q.shippingFee());
    res.put(
        "shippingDiscount",
        !pos && q.subtotal().compareTo(BigDecimal.ZERO) > 0 && q.shippingFee().compareTo(BigDecimal.ZERO) == 0
            ? DEFAULT_SHIPPING_FEE
            : BigDecimal.ZERO);
    res.put("shippingFreeReason", q.shippingFreeReason());
    res.put("total", q.total());
    res.put("discountDetails", q.discountDetails());
    if (q.voucherWarning() != null) res.put("voucherWarning", q.voucherWarning());
    return res;
  }

  private QuoteInternal computeQuote(
      String customerId,
      Map<String, Integer> items,
      Map<String, BigDecimal> prices,
      Map<String, String> itemSessionHints,
      String rawVoucherCode,
      String requestSessionId,
      boolean pos,
      boolean consume) {
    BigDecimal subtotal = BigDecimal.ZERO;
    for (var e : items.entrySet()) {
      subtotal = subtotal.add(prices.get(e.getKey()).multiply(BigDecimal.valueOf(e.getValue())));
    }

    // 1. Resolve VTON session links and detect eligible VTON Combo (-5%) pairs
    var variantSessionMap = new HashMap<String, String>();
    var comboVariantSet = new LinkedHashSet<String>();
    String comboSessionId = null;
    BigDecimal comboEligibleSubtotal = BigDecimal.ZERO;
    var variantList = new ArrayList<>(items.keySet());

    // Query valid VTON pairs (PhienThuDoAo THANH_CONG + GoiYPhoiDo) owned by customer or matching explicit sessionId
    List<Map<String, Object>> candidatePairs = new ArrayList<>();
    if (variantList.size() >= 2) {
      if (customerId != null && !customerId.isBlank()) {
        candidatePairs.addAll(
            db.rows(
                "SELECT p.MaPhienThuDo AS sessionId, p.MaBienThe AS mainVariant, g.MaBienTheGoiY AS recVariant"
                    + " FROM PhienThuDoAo p JOIN GoiYPhoiDo g ON g.MaPhienThuDo = p.MaPhienThuDo"
                    + " WHERE p.MaNguoiDung = ? AND p.TrangThaiPhien = 'THANH_CONG'"
                    + " ORDER BY p.ThoiGianKhoiTao DESC",
                customerId));
      }
      if (requestSessionId != null && !requestSessionId.isBlank()) {
        candidatePairs.addAll(
            db.rows(
                "SELECT p.MaPhienThuDo AS sessionId, p.MaBienThe AS mainVariant, g.MaBienTheGoiY AS recVariant"
                    + " FROM PhienThuDoAo p JOIN GoiYPhoiDo g ON g.MaPhienThuDo = p.MaPhienThuDo"
                    + " WHERE p.MaPhienThuDo = ? AND p.TrangThaiPhien = 'THANH_CONG'",
                requestSessionId.trim()));
      }
    }
    for (var pair : candidatePairs) {
      String mainV = Db.text(pair, "mainVariant");
      String recV = Db.text(pair, "recVariant");
      String sid = Db.text(pair, "sessionId");
      if (!mainV.equals(recV)
          && items.containsKey(mainV)
          && items.containsKey(recV)
          && !comboVariantSet.contains(mainV)
          && !comboVariantSet.contains(recV)) {
        int pairQty = Math.min(items.get(mainV), items.get(recV));
        BigDecimal pairTotal =
            prices.get(mainV).add(prices.get(recV)).multiply(BigDecimal.valueOf(pairQty));
        comboEligibleSubtotal = comboEligibleSubtotal.add(pairTotal);
        comboVariantSet.add(mainV);
        comboVariantSet.add(recV);
        if (comboSessionId == null) comboSessionId = sid;
        variantSessionMap.putIfAbsent(mainV, sid);
        variantSessionMap.putIfAbsent(recV, sid);
      }
    }

    // Attach explicit or historical VTON session IDs to remaining lines for analytics attribution
    for (String vid : variantList) {
      if (variantSessionMap.containsKey(vid)) continue;
      String hint = itemSessionHints.get(vid);
      if (hint == null || hint.isBlank()) hint = requestSessionId;
      if (hint != null && !hint.isBlank()) {
        long exists = db.count("SELECT COUNT(*) FROM PhienThuDoAo WHERE MaPhienThuDo=?", hint.trim());
        if (exists > 0) {
          variantSessionMap.put(vid, hint.trim());
          continue;
        }
      }
      if (customerId != null && !customerId.isBlank()) {
        var recent =
            db.rows(
                "SELECT MaPhienThuDo FROM PhienThuDoAo WHERE MaNguoiDung=? AND MaBienThe=? AND TrangThaiPhien='THANH_CONG' ORDER BY ThoiGianKhoiTao DESC LIMIT 1",
                customerId,
                vid);
        if (!recent.isEmpty()) {
          variantSessionMap.put(vid, Db.text(recent.getFirst(), "MaPhienThuDo"));
        }
      }
    }

    BigDecimal comboDiscount =
        comboEligibleSubtotal.compareTo(BigDecimal.ZERO) > 0
            ? comboEligibleSubtotal.multiply(BigDecimal.valueOf(5)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    BigDecimal subtotalAfterCombo = subtotal.subtract(comboDiscount).max(BigDecimal.ZERO);

    // 2. Apply Voucher on (subtotal - comboDiscount) per business rule
    BigDecimal voucherDiscount = BigDecimal.ZERO;
    String promotionId = null;
    String voucherCode = null;
    String voucherName = null;
    String voucherType = null;
    String voucherWarning = null;
    boolean freeShipVoucher = false;

    String cleanCode = rawVoucherCode == null ? "" : rawVoucherCode.trim();
    if (!cleanCode.isBlank()) {
      boolean alreadyUsed = false;
      if (customerId != null && !customerId.isBlank()) {
        long usedInOtherOrder =
            db.count(
                "SELECT COUNT(*) FROM DonHang WHERE MaNguoiDung=? AND MaKhuyenMai=(SELECT MaKhuyenMai FROM KhuyenMai WHERE MaPhieuGiamGia=?) AND TrangThaiDonHang NOT IN ('DA_HUY', 'TRA_HANG')",
                customerId,
                cleanCode);
        alreadyUsed = usedInOtherOrder > 0;
        if (consume) {
          Problem.require(!alreadyUsed, 409, "VOUCHER_ALREADY_USED", "Bạn đã sử dụng mã voucher này trong một đơn hàng trước đó.");
        }
      }
      if (alreadyUsed) {
        voucherWarning = "Bạn đã sử dụng mã " + cleanCode + " trong đơn hàng trước đó.";
      } else {
        var promoRows =
            db.rows(
                "SELECT * FROM KhuyenMai WHERE MaPhieuGiamGia=? AND TrangThai='DANG_DIEN_RA' AND"
                    + " NgayBatDau<=CURRENT_TIMESTAMP AND NgayKetThuc>=CURRENT_TIMESTAMP"
                    + (consume ? " FOR UPDATE" : ""),
                cleanCode);
        if (promoRows.isEmpty()) {
          if (consume) {
            throw new Problem(404, "NOT_FOUND", "Mã voucher không tồn tại hoặc đã hết hạn.");
          }
          voucherWarning = "Mã voucher " + cleanCode + " không hợp lệ hoặc đã hết hạn.";
        } else {
          var p = promoRows.getFirst();
          int used = ((Number) p.get("SoLuongDaDung")).intValue();
          int issued = ((Number) p.get("SoLuongPhatHanh")).intValue();
          BigDecimal minTotal = Db.money(p, "GiaTriDonHangToiThieu");
          boolean eligible = used < issued && subtotalAfterCombo.compareTo(minTotal) >= 0;
          if (!eligible) {
            if (consume) {
              throw new Problem(409, "VOUCHER_UNAVAILABLE", "Voucher hết lượt hoặc chưa đạt đơn tối thiểu.");
            }
            voucherWarning =
                used >= issued
                    ? "Voucher " + cleanCode + " đã hết lượt sử dụng."
                    : "Đơn sau ưu đãi combo cần đạt tối thiểu " + minTotal.setScale(0, RoundingMode.HALF_UP) + "đ để áp dụng " + cleanCode + ".";
          } else {
            voucherCode = Db.text(p, "MaPhieuGiamGia");
            voucherName = Db.text(p, "TenChuongTrinh");
            voucherType = Db.text(p, "LoaiGiamGia");
            promotionId = Db.text(p, "MaKhuyenMai");
            BigDecimal value = Db.money(p, "GiaTriGiam");
            if ("MIEN_PHI_VAN_CHUYEN".equals(voucherType)) {
              freeShipVoucher = true;
            } else {
              BigDecimal amount =
                  switch (voucherType) {
                    case "PHAN_TRAM" ->
                        subtotalAfterCombo.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    case "TIEN_MAT_TRUC_TIEP" -> value;
                    default -> BigDecimal.ZERO;
                  };
              if (p.get("GiamToiDa") != null) amount = amount.min(Db.money(p, "GiamToiDa"));
              voucherDiscount = amount.min(subtotalAfterCombo);
            }
            if (consume) {
              db.exec("UPDATE KhuyenMai SET SoLuongDaDung=SoLuongDaDung+1 WHERE MaKhuyenMai=?", promotionId);
            }
          }
        }
      }
    }

    // 3. Shipping fee calculation
    BigDecimal shippingFee = BigDecimal.ZERO;
    String shippingFreeReason = null;
    if (!pos && subtotal.compareTo(BigDecimal.ZERO) > 0) {
      if (freeShipVoucher) {
        shippingFee = BigDecimal.ZERO;
        shippingFreeReason = "Voucher " + voucherCode;
      } else if (subtotal.compareTo(FREE_SHIP_THRESHOLD) >= 0) {
        shippingFee = BigDecimal.ZERO;
        shippingFreeReason = "Miễn phí vận chuyển đơn từ 1.000.000đ";
      } else {
        shippingFee = DEFAULT_SHIPPING_FEE;
      }
    }

    // 4. Discount breakdown list
    var discountDetails = new ArrayList<Map<String, Object>>();
    if (comboDiscount.compareTo(BigDecimal.ZERO) > 0) {
      discountDetails.add(
          Map.of(
              "code", "COMBO_VTON_5",
              "label", "Ưu đãi Combo phối đồ AI VTON (-5%)",
              "kind", "COMBO_VTON",
              "amount", comboDiscount));
    }
    if (voucherDiscount.compareTo(BigDecimal.ZERO) > 0 && voucherCode != null) {
      discountDetails.add(
          Map.of(
              "code", voucherCode,
              "label", voucherName != null && !voucherName.isBlank() ? voucherName : "Voucher " + voucherCode,
              "kind", "VOUCHER",
              "amount", voucherDiscount));
    }
    if (freeShipVoucher && !pos && subtotal.compareTo(BigDecimal.ZERO) > 0 && subtotal.compareTo(FREE_SHIP_THRESHOLD) < 0) {
      discountDetails.add(
          Map.of(
              "code", voucherCode,
              "label", "Miễn phí vận chuyển (" + voucherCode + ")",
              "kind", "SHIPPING",
              "amount", DEFAULT_SHIPPING_FEE));
    }

    BigDecimal totalDiscount = comboDiscount.add(voucherDiscount);
    BigDecimal total = subtotal.subtract(totalDiscount).add(shippingFee).max(BigDecimal.ZERO);

    return new QuoteInternal(
        subtotal,
        comboDiscount,
        new ArrayList<>(comboVariantSet),
        comboSessionId,
        voucherDiscount,
        voucherCode,
        voucherName,
        voucherType,
        promotionId,
        totalDiscount,
        shippingFee,
        shippingFreeReason,
        total,
        discountDetails,
        variantSessionMap,
        voucherWarning);
  }

  private void markSuggestionsPurchased(String customerId, String orderId, String fallbackSessionId) {
    if (customerId != null && !customerId.isBlank()) {
      db.exec(
          "UPDATE GoiYPhoiDo SET TrangThaiTuongTac='DA_MUA'"
              + " WHERE TrangThaiTuongTac <> 'DA_MUA'"
              + " AND MaBienTheGoiY IN (SELECT MaBienThe FROM ChiTietDonHang WHERE MaDonHang=?)"
              + " AND MaPhienThuDo IN (SELECT MaPhienThuDo FROM PhienThuDoAo WHERE MaNguoiDung=?)",
          orderId,
          customerId);
    } else if (fallbackSessionId != null && !fallbackSessionId.isBlank()) {
      db.exec(
          "UPDATE GoiYPhoiDo SET TrangThaiTuongTac='DA_MUA'"
              + " WHERE TrangThaiTuongTac <> 'DA_MUA'"
              + " AND MaPhienThuDo=?"
              + " AND MaBienTheGoiY IN (SELECT MaBienThe FROM ChiTietDonHang WHERE MaDonHang=?)",
          fallbackSessionId.trim(),
          orderId);
    }
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
    return cancel(id, actor, customer, returned, true);
  }

  /** Hủy/nhận hoàn có tùy chọn phục hồi tồn kho khả dụng hay phân loại hàng lỗi. */
  @Transactional
  public Map<String, Object> cancel(
      String id, String actor, boolean customer, boolean returned, boolean restock) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    String state = Db.text(o, "TrangThaiDonHang");
    String target = returned ? "TRA_HANG" : "DA_HUY";
    if (customer)
      Problem.require(actor.equals(o.get("MaNguoiDung")), 404, "NOT_FOUND", "Không có đơn hàng.");
    if (state.equals(target) && (!returned || db.count(
        "SELECT COUNT(*) FROM BienDongKho WHERE MaThamChieu=? AND (LoaiBienDong='NHAP_KHO_HANG_HOAN'"
            + " OR GhiChu='Hàng hoàn lỗi - chuyển kho chờ thanh lý')"
            + " AND COALESCE(GhiChu,'') <> 'Hủy giữ tồn'", id) > 0)) return detail(id);
    Problem.require(
        returned
            ? Set.of("TRA_HANG", "HOAN_TAT").contains(state)
            : (customer
                ? state.equals("CHO_XAC_NHAN")
                : Set.of("CHO_XAC_NHAN", "DANG_DONG_GOI").contains(state)),
        409,
        "ORDER_STATE_INVALID",
        "Không thể hủy/nhận hoàn ở trạng thái này.");
    if (restock) {
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
    } else {
      for (var line :
          db.rows(
              "SELECT MaBienThe,SoLuong FROM ChiTietDonHang WHERE MaDonHang=? ORDER BY MaBienThe",
              id))
        stock.move(
            Db.text(line, "MaBienThe"),
            0,
            "NHAP_KHO_HANG_HOAN",
            id,
            actor,
            "Hàng hoàn lỗi - chuyển kho chờ thanh lý");
    }
    db.exec("UPDATE DonHang SET TrangThaiDonHang=?,SoTienThuHo=0 WHERE MaDonHang=?", target, id);
    if (returned && "HOAN_TAT".equals(state) && o.get("MaNguoiDung") != null) {
      reverseOrderLoyalty(Db.text(o, "MaNguoiDung"), id);
    }
    if (o.get("MaKhuyenMai") != null)
      db.exec(
          "UPDATE KhuyenMai SET SoLuongDaDung=GREATEST(0,SoLuongDaDung-1) WHERE MaKhuyenMai=?",
          o.get("MaKhuyenMai"));
    db.exec("UPDATE NguoiDung_KhuyenMai SET TrangThai='CHUA_DUNG', MaDonHangDaDung=NULL, NgaySuDung=NULL WHERE MaDonHangDaDung=?", id);
    // Hoàn hàng đã thanh toán vẫn cần đối soát tiền; không tự ghi DA_HOAN_TIEN.
    db.audit(actor, returned ? "ORDER_RETURN_RECEIVED" : "ORDER_CANCEL", id);
    if (o.get("MaNguoiDung") != null) {
      String custMsg = returned
          ? "Kho đã tiếp nhận kiện hàng hoàn trả cho đơn #" + id + "."
          : (actor == null
              ? "Đơn hàng #" + id + " đã tự động hủy do quá thời hạn thanh toán VietQR 15 phút."
              : (customer
                  ? "Đơn hàng #" + id + " đã được hủy theo yêu cầu của bạn."
                  : "Đơn hàng #" + id + " đã được cửa hàng hủy."));
      notice(Db.text(o, "MaNguoiDung"), id, custMsg);
    }
    if (returned) {
      notifyRole(
          "ROLE_SHOP_ADMIN",
          "Đã nhận hàng hoàn đơn #" + id,
          "Kho đã tiếp nhận kiện hàng hoàn của đơn #" + id + (restock ? " và hoàn lại tồn kho." : " (phân loại hàng lỗi)."),
          "DON_HANG",
          "/admin/dashboard?tab=operations&order=" + id);
    } else if (customer) {
      notifyRole(
          "ROLE_STAFF_WAREHOUSE",
          "Khách hủy đơn #" + id,
          "Khách hàng vừa hủy đơn #" + id + ". Đã tự động hoàn giữ tồn kho.",
          "DON_HANG",
          "/warehouse/fulfillment?tab=pick");
      notifyRole(
          "ROLE_SHOP_ADMIN",
          "Khách hủy đơn #" + id,
          "Khách hàng vừa hủy đơn online #" + id + ".",
          "DON_HANG",
          "/admin/dashboard?tab=operations&order=" + id);
    }
    return detail(id);
  }

  /** Ghi nhận giao hàng thất bại, tăng số lần giao và tự động chuyển hoàn nếu đủ 3 lần hoặc khách từ chối. */
  @Transactional
  public Map<String, Object> deliveryFailed(String id, String actor, String reason, String note) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    Problem.require(!o.isEmpty(), 404, "NOT_FOUND", "Không tìm thấy đơn hàng.");
    String state = Db.text(o, "TrangThaiDonHang");
    boolean isAdmin = db.count(
        "SELECT COUNT(*) FROM NguoiDung_VaiTro nv JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro WHERE nv.MaNguoiDung=? AND v.TenVaiTro='ROLE_SHOP_ADMIN'",
        actor) > 0;
    Problem.require(
        (isAdmin || actor.equals(o.get("MaNguoiGiaoHang"))) && state.equals("DANG_GIAO_HANG"),
        409,
        "ORDER_STATE_INVALID",
        "Không phải đơn bạn đang giao.");
    int soLanGiao = ((Number) o.getOrDefault("SoLanGiao", 0)).intValue();
    boolean shouldReturn = soLanGiao >= 3 || "TU_CHOI_NHAN".equals(reason);
    String targetStatus = shouldReturn ? "TRA_HANG" : "DANG_GIAO_HANG";
    String fullNote = (note != null && !note.isBlank()) ? note : reason;
    db.exec(
        "UPDATE DonHang SET TrangThaiDonHang=?, SoLanGiao=SoLanGiao+?, GhiChuDonHang=? WHERE MaDonHang=?",
        targetStatus,
        shouldReturn ? 0 : 1,
        fullNote,
        id);
    db.audit(actor, "ORDER_DELIVERY_FAILED", id);
    if (o.get("MaNguoiDung") != null) {
      notice(Db.text(o, "MaNguoiDung"), id, "Đơn giao chưa thành công: " + fullNote);
    }
    return detail(id);
  }

  /** Đóng gói/giao hàng do từng endpoint staff kiểm tra quyền và assigned shipper. */
  @Transactional
  public Map<String, Object> transition(String id, String actor, String action, String shipper) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    String state = Db.text(o, "TrangThaiDonHang");
    switch (action) {
      case "pack" -> {
        requireReadyPayment(o);
        Problem.require(
            state.equals("CHO_XAC_NHAN"),
            409,
            "ORDER_STATE_INVALID",
            "Đơn chưa ở trạng thái chờ xác nhận.");
        db.exec("UPDATE DonHang SET TrangThaiDonHang='DANG_DONG_GOI' WHERE MaDonHang=?", id);
      }
      case "assign", "pickup" -> {
        requireReadyPayment(o);
        Problem.require(
            state.equals("DANG_DONG_GOI") && o.get("MaNguoiGiaoHang") == null,
            409, "ORDER_STATE_INVALID", "Kiện hàng chưa sẵn sàng bàn giao.");
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
        for (var line : db.rows(
            "SELECT MaBienThe FROM ChiTietDonHang WHERE MaDonHang=? ORDER BY MaBienThe", id))
          stock.move(Db.text(line, "MaBienThe"), 0, "XUAT_KHO_GIAO_HANG", id, actor, "Bàn giao kiện hàng");
        if (shipper != null && !shipper.isBlank()) {
          noticeDirect(
              shipper,
              "Đơn giao mới #" + id,
              "Kho vừa bàn giao kiện hàng #" + id + " cho tuyến giao của bạn.",
              "DON_HANG",
              "/shipper/deliveries?order=" + id);
        }
      }
      case "deliver" -> {
        boolean isAdmin = db.count(
            "SELECT COUNT(*) FROM NguoiDung_VaiTro nv JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro WHERE nv.MaNguoiDung=? AND v.TenVaiTro='ROLE_SHOP_ADMIN'",
            actor) > 0;
        Problem.require(
            (isAdmin || actor.equals(o.get("MaNguoiGiaoHang"))) && state.equals("DANG_GIAO_HANG"),
            409,
            "ORDER_STATE_INVALID",
            "Không phải đơn bạn đang giao.");
        db.exec(
            "UPDATE DonHang SET"
                + " TrangThaiDonHang='HOAN_TAT',TrangThaiThanhToan='DA_THANH_TOAN',ThoiGianThanhToan=COALESCE(ThoiGianThanhToan,CURRENT_TIMESTAMP),SoTienThuHo=0"
                + " WHERE MaDonHang=?",
            id);
        db.exec("UPDATE NguoiDung_KhuyenMai SET TrangThai='DA_DUNG' WHERE MaDonHangDaDung=?", id);
        if (o.get("MaNguoiDung") != null) {
          String owner = Db.text(o, "MaNguoiDung");
          markSuggestionsPurchased(owner, id, null);
          awardOrderLoyalty(owner, id);
        }
      }
      case "failed" -> {
        boolean isAdmin = db.count(
            "SELECT COUNT(*) FROM NguoiDung_VaiTro nv JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro WHERE nv.MaNguoiDung=? AND v.TenVaiTro='ROLE_SHOP_ADMIN'",
            actor) > 0;
        Problem.require(
            (isAdmin || actor.equals(o.get("MaNguoiGiaoHang"))) && state.equals("DANG_GIAO_HANG"),
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
    if (o.get("MaNguoiDung") != null) {
      String msg = switch (action) {
        case "pack" -> "Đơn hàng #" + id + " đã được xác nhận và đang được kho đóng gói.";
        case "assign", "pickup" -> "Đơn hàng #" + id + " đã bàn giao cho shipper và đang trên đường giao đến bạn.";
        case "deliver" -> "Đơn hàng #" + id + " đã giao thành công. Hãy đánh giá sản phẩm để tích điểm nhé!";
        case "failed" -> "Đơn hàng #" + id + " tạm hoàn về kho chờ điều phối giao lại.";
        default -> "Đơn hàng #" + id + " đã cập nhật trạng thái.";
      };
      notice(Db.text(o, "MaNguoiDung"), id, msg);
    }
    return detail(id);
  }

  private void requireReadyPayment(Map<String, Object> order) {
    Problem.require("ONLINE_APP".equals(order.get("KenhBanHang"))
        && (!"VIETQR_CHUYEN_KHOAN_ONLINE".equals(order.get("PhuongThucThanhToan"))
            || "DA_THANH_TOAN".equals(order.get("TrangThaiThanhToan"))),
        409, "ORDER_NOT_READY", "Đơn online chưa đủ điều kiện xử lý kho.");
  }

  /** Quy đổi 1 điểm tích lũy cho mỗi 10.000đ tiền hàng thực trả (không tính phí ship). */
  static int loyaltyPointsForAmount(BigDecimal netPaid) {
    if (netPaid == null || netPaid.compareTo(BigDecimal.valueOf(10_000)) < 0) return 0;
    return netPaid.divideToIntegralValue(BigDecimal.valueOf(10_000)).intValue();
  }

  /** Xét nâng hạng theo thang Đồng (<100), Bạc (100..299), Vàng (300..499), Kim cương (>=500); giữ nguyên nếu đang có hạng đặc cách cao hơn. */
  static String tierForPoints(int points, String currentTier) {
    String earnedTier =
        points >= 500 ? "KIM_CUONG" : points >= 300 ? "VANG" : points >= 100 ? "BAC" : "DONG";
    return tierRank(currentTier) > tierRank(earnedTier) ? currentTier.trim().toUpperCase(Locale.ROOT) : earnedTier;
  }

  private static int tierRank(String tier) {
    if (tier == null) return 0;
    return switch (tier.trim().toUpperCase(Locale.ROOT)) {
      case "KIM_CUONG" -> 3;
      case "VANG" -> 2;
      case "BAC" -> 1;
      default -> 0;
    };
  }

  private int earnedPointsOfOrder(String orderId) {
    var row =
        db.one(
            "SELECT GREATEST(0, COALESCE((SELECT SUM(c.SoLuong * c.DonGiaLucDatHang) FROM"
                + " ChiTietDonHang c WHERE c.MaDonHang=d.MaDonHang), 0) - d.TienGiamGia) AS netPaid"
                + " FROM DonHang d WHERE d.MaDonHang=?",
            orderId);
    return loyaltyPointsForAmount(Db.money(row, "netPaid"));
  }

  private void awardOrderLoyalty(String userId, String orderId) {
    if (userId == null || userId.isBlank()) return;
    int earned = earnedPointsOfOrder(orderId);
    var user =
        db.one(
            "SELECT DiemTichLuy, HangThanhVien FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",
            userId);
    int currentPoints = ((Number) user.getOrDefault("DiemTichLuy", 0)).intValue();
    int nextPoints = Math.max(0, currentPoints + earned);
    String currentTier = Db.text(user, "HangThanhVien");
    String nextTier = tierForPoints(nextPoints, currentTier);
    if (nextPoints != currentPoints || !nextTier.equals(currentTier)) {
      db.exec(
          "UPDATE NguoiDung SET DiemTichLuy=?, HangThanhVien=? WHERE MaNguoiDung=?",
          nextPoints,
          nextTier,
          userId);
    }
  }

  private void reverseOrderLoyalty(String userId, String orderId) {
    if (userId == null || userId.isBlank()) return;
    int earned = earnedPointsOfOrder(orderId);
    if (earned <= 0) return;
    var user =
        db.one(
            "SELECT DiemTichLuy FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE",
            userId);
    int currentPoints = ((Number) user.getOrDefault("DiemTichLuy", 0)).intValue();
    int nextPoints = Math.max(0, currentPoints - earned);
    if (nextPoints != currentPoints) {
      db.exec("UPDATE NguoiDung SET DiemTichLuy=? WHERE MaNguoiDung=?", nextPoints, userId);
    }
  }

  @Transactional
  public void expirePayment(String id) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    if (o.get("MaDonHang") != null
        && db.count("SELECT COUNT(*) FROM DonHang WHERE MaDonHang=? AND " + EXPIRED_PAYMENT, id) == 1)
      cancel(id, null, false, false);
  }

  static final String EXPIRED_PAYMENT =
      "KenhBanHang='ONLINE_APP' AND PhuongThucThanhToan='VIETQR_CHUYEN_KHOAN_ONLINE'"
          + " AND TrangThaiDonHang='CHO_XAC_NHAN' AND TrangThaiThanhToan='CHUA_THANH_TOAN'"
          + " AND NgayTao <= CURRENT_TIMESTAMP - INTERVAL 15 MINUTE";

  /** Chủ shop xác nhận sau khi đối soát giao dịch thực nhận. */
  @Transactional
  public Map<String, Object> markPaidOnline(String id, String transactionRef) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    Problem.require(
        "VIETQR_CHUYEN_KHOAN_ONLINE".equals(o.get("PhuongThucThanhToan")),
        400,
        "PAYMENT_METHOD_INVALID",
        "Đơn hàng này không dùng phương thức VietQR.");
    Problem.require("CHO_XAC_NHAN".equals(o.get("TrangThaiDonHang"))
        && db.count("SELECT COUNT(*) FROM DonHang WHERE MaDonHang=?"
            + " AND NgayTao > CURRENT_TIMESTAMP - INTERVAL 15 MINUTE", id) == 1,
        409, "PAYMENT_EXPIRED", "Đơn đã hết hạn hoặc đã đóng. Hãy đối soát khoản tiền đến muộn.");
    Problem.require(db.count("SELECT COUNT(*) FROM DonHang WHERE MaGiaoDichNgoai=?", transactionRef) == 0,
        409, "PAYMENT_REFERENCE_USED", "Mã giao dịch này đã được sử dụng.");
    Problem.require(
        "CHUA_THANH_TOAN".equals(o.get("TrangThaiThanhToan")),
        409,
        "ALREADY_PAID",
        "Đơn hàng đã được ghi nhận thanh toán trước đó.");
    db.exec(
        "UPDATE DonHang SET TrangThaiThanhToan='DA_THANH_TOAN', ThoiGianThanhToan=CURRENT_TIMESTAMP,"
            + " MaGiaoDichNgoai=? WHERE MaDonHang=?",
        transactionRef,
        id);
    db.audit("SYSTEM", "ORDER_PAID_VIETQR", id);
    if (o.get("MaNguoiDung") != null) {
      notice(Db.text(o, "MaNguoiDung"), id, "Đã ghi nhận thanh toán VietQR thành công.");
    }
    return detail(id);
  }

  /** Thông báo gọn, không nhét địa chỉ/số điện thoại vào log. */
  private void notice(String user, String id, String message) {
    noticeDirect(user, "Cập nhật đơn hàng #" + id, message, "DON_HANG", "/orders/" + id);
  }

  private void noticeDirect(String userId, String title, String content, String type, String link) {
    if (notifications != null) {
      notifications.notifyUser(userId, title, content, type, link);
      return;
    }
    try {
      db.exec(
          "INSERT INTO ThongBao(MaThongBao,MaNguoiDung,TieuDe,NoiDung,LoaiThongBao,LienKetDieuHuong)"
              + " VALUES(?,?,?,?,?,?)",
          Db.id("TB"),
          userId,
          title,
          content,
          type,
          link);
    } catch (Exception ignored) {}
  }

  private void notifyRole(String roleName, String title, String content, String type, String link) {
    if (notifications != null) {
      notifications.notifyRole(roleName, title, content, type, link);
      return;
    }
    try {
      var users =
          db.rows(
              "SELECT DISTINCT u.MaNguoiDung AS id FROM NguoiDung u"
                  + " JOIN NguoiDung_VaiTro nv ON nv.MaNguoiDung=u.MaNguoiDung"
                  + " JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro"
                  + " WHERE v.TenVaiTro=? AND u.TrangThaiHoatDong='HOAT_DONG'",
              roleName);
      for (var u : users) {
        noticeDirect(Db.text(u, "id"), title, content, type, link);
      }
    } catch (Exception ignored) {}
  }
}
