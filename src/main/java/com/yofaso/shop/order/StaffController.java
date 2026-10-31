package com.yofaso.shop.order;

import com.yofaso.shop.auth.*;
import com.yofaso.shop.catalog.CatalogService;
import com.yofaso.shop.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Portal nhân sự; quyền theo từng nghiệp vụ, không mở chung một API admin cho mọi staff. */
@RestController
@RequestMapping("/api/v1")
public class StaffController {
  public record Adjustment(
      @NotBlank @Size(max = 20) String variantId,
      @Min(-100000) @Max(100000) int delta,
      @NotBlank @Size(max = 255) String reason) {}

  public record Assignment(@NotBlank @Size(max = 20) String shipperId) {}

  public record ShiftMoney(
      @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal amount,
      @Size(max = 1000) String note) {}

  public record Staff(
      @Valid @NotNull AuthController.Register account,
      @NotEmpty @Size(max = 5) List<@Pattern(regexp = "VT00[1-5]") String> roleIds) {}

  public record Roles(
      @NotEmpty @Size(max = 5) List<@Pattern(regexp = "VT00[1-5]") String> roleIds) {}

  public record AccountStatus(
      @NotNull @Pattern(regexp = "HOAT_DONG|KHOA_TAM_THOI") String status) {}

  public record Promotion(
      @NotBlank @Pattern(regexp = "[A-Z0-9_-]{3,50}") String code,
      @NotBlank @Size(max = 150) String name,
      @NotNull @Pattern(regexp = "PHAN_TRAM|TIEN_MAT_TRUC_TIEP|MIEN_PHI_VAN_CHUYEN") String type,
      @NotNull @DecimalMin("0") BigDecimal value,
      @NotNull @DecimalMin("0") BigDecimal minimumTotal,
      @DecimalMin("0") BigDecimal maxDiscount,
      @Min(1) int quantity,
      @NotNull java.time.Instant startsAt,
      @NotNull java.time.Instant endsAt) {}

  private final Db db;
  private final OrderService orders;
  private final InventoryService stock;
  private final Idempotency keys;
  private final PasswordEncoder passwords;

  /** Nhận các service dùng lại giữa POS, kho và admin. */
  public StaffController(
      Db db,
      OrderService orders,
      InventoryService stock,
      PasswordEncoder passwords,
      Idempotency keys) {
    this.db = db;
    this.orders = orders;
    this.stock = stock;
    this.keys = keys;
    this.passwords = passwords;
  }

  /** Danh sách quyền có sẵn; quyền mới cần có chính sách endpoint tương ứng. */
  @GetMapping("/admin/roles")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> roles() {
    return db.rows("SELECT MaVaiTro AS roleId,TenVaiTro AS name,TenHienThi AS label FROM VaiTro");
  }

  /** Tài khoản nhân sự do chủ shop tạo; không dùng endpoint đăng ký để tự nhận role. */
  @PostMapping("/admin/users")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> staff(@Valid @RequestBody Staff b, Authentication a) {
    var u = b.account();
    String email=com.yofaso.shop.auth.AccountEmail.reserve(db,u.email(),"");
    AuthService.validatePassword(u.password());
    String id = Db.id("ND");
    db.exec(
        "INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai,Email)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        u.username(),
        passwords.encode(u.password()),
        u.fullName()==null || u.fullName().isBlank() ? u.username() : u.fullName(),
        u.phone(),
        email);
    for (String role : new HashSet<>(b.roleIds()))
      db.exec("INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro) VALUES(?,?)", id, role);
    db.audit(a.getName(), "STAFF_CREATE", id);
    return Map.of("userId", id);
  }

  /** Danh sách user không có hash mật khẩu hoặc token. */
  @GetMapping("/admin/users")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> users(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return db.rows(
        "SELECT MaNguoiDung AS userId,TenDangNhap AS username,HoVaTen AS fullName,TrangThaiHoatDong"
            + " AS status FROM NguoiDung ORDER BY MaNguoiDung LIMIT ? OFFSET ?",
        size,
        Db.offset(page, size));
  }

  /** Đổi role và vô hiệu token cũ; không cho admin tự tước quyền của mình. */
  @PutMapping("/admin/users/{id}/roles")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> grant(
      @PathVariable String id, @Valid @RequestBody Roles b, Authentication a) {
    // Cùng thứ tự khóa role → user với writer email để tránh vòng chờ khi grant CUSTOMER.
    db.one("SELECT MaVaiTro FROM VaiTro WHERE TenVaiTro='ROLE_CUSTOMER' FOR UPDATE");
    Problem.require(
        !id.equals(a.getName()),
        409,
        "SELF_CHANGE_FORBIDDEN",
        "Không tự đổi quyền admin hiện tại.");
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", id);
    db.exec("DELETE FROM NguoiDung_VaiTro WHERE MaNguoiDung=?", id);
    for (String r : new HashSet<>(b.roleIds()))
      db.exec("INSERT INTO NguoiDung_VaiTro(MaNguoiDung,MaVaiTro) VALUES(?,?)", id, r);

    db.runtime().bump(id);
    db.audit(a.getName(), "USER_ROLES", id);
    return Map.of("updated", true);
  }

  /** Khóa tài khoản có hiệu lực ngay vì JWT kiểm tra lại DB mỗi request. */
  @PatchMapping("/admin/users/{id}/status")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> status(
      @PathVariable String id, @Valid @RequestBody AccountStatus b, Authentication a) {
    Problem.require(
        !id.equals(a.getName()),
        409,
        "SELF_CHANGE_FORBIDDEN",
        "Không tự khóa tài khoản admin đang dùng.");
    Problem.require(
        db.exec(
                "UPDATE NguoiDung SET TrangThaiHoatDong=? WHERE"
                    + " MaNguoiDung=?",
                b.status(),
                id)
            == 1,
        404,
        "NOT_FOUND",
        "Không có tài khoản.");
    db.runtime().bump(id);
    db.audit(a.getName(), "USER_STATUS", id);
    return Map.of("updated", true);
  }

  /** Tra barcode nội bộ có vị trí kệ, không mở cho khách vãng lai. */
  @GetMapping("/staff/variants/by-sku")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE')")
  public Map<String, Object> barcode(@RequestParam String sku) {
    return db.one(CatalogService.VARIANT + " WHERE MaQuanLyHangHoa=?", sku);
  }

  /** Khách tại quầy chỉ tra bằng số điện thoại chính xác, không liệt kê cả database. */
  @GetMapping("/pos/customers")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public Map<String, Object> customer(@RequestParam String phone) {
    return db.one(
        "SELECT MaNguoiDung AS userId,HoVaTen AS fullName,DiemTichLuy AS points,HangThanhVien AS"
            + " membership FROM NguoiDung WHERE SoDienThoai=?",
        phone);
  }

  /** Cảnh báo tồn thấp dùng chung POS/kho, phân trang có giới hạn. */
  @GetMapping("/staff/inventory/low-stock")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE')")
  public List<Map<String, Object>> lowStock(
      @RequestParam(defaultValue = "5") int threshold,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Problem.require(
        threshold >= 0 && threshold <= 1000, 422, "THRESHOLD_INVALID", "Ngưỡng 0..1000.");
    return db.rows(
        CatalogService.VARIANT
            + " WHERE SoLuongTonKho<=? ORDER BY SoLuongTonKho,MaBienThe LIMIT ? OFFSET ?",
        threshold,
        size,
        Db.offset(page, size));
  }

  /** Nhập hàng/cân kho ghi dấu vết và chặn tồn âm. */
  @PostMapping("/staff/inventory/adjustments")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  @Transactional
  public Map<String, Object> adjust(
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Adjustment b,
      Authentication a) {
    String old = keys.existing(a.getName(), "INVENTORY_ADJUST", key, b);
    if (old != null) return Map.of("updated", true, "movementId", old);
    String movement =
        stock.moveWithId(b.variantId(), b.delta(), "KIEM_KE_CAN_KHO", null, a.getName(), b.reason(),keys.resourceId(a.getName(),"INVENTORY_ADJUST",key,"BDK"));
    keys.complete(a.getName(), "INVENTORY_ADJUST", key, movement);
    return Map.of("updated", true, "movementId", movement);
  }

  /** Sổ cái kho theo SKU. */
  @GetMapping("/staff/inventory/movements")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public List<Map<String, Object>> movements(
      @RequestParam String variantId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return db.rows(
        "SELECT MaBienDong AS movementId,LoaiBienDong AS type,SoLuongThayDoi AS"
            + " delta,SoLuongTonTruoc AS beforeStock,SoLuongTonSau AS afterStock,MaThamChieu AS"
            + " referenceId,GhiChu AS reason,NgayThucHien AS createdAt FROM BienDongKho WHERE"
            + " MaBienThe=? ORDER BY NgayThucHien DESC,MaBienDong LIMIT ? OFFSET ?",
        variantId,
        size,
        Db.offset(page, size));
  }

  /** Đơn staff: shipper chỉ thấy đơn được phân, cashier chỉ thấy đơn mình lập. */
  @GetMapping("/staff/orders")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')")
  public List<Map<String, Object>> staffOrders(
      Authentication a,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    String where =
        has(a, "SHOP_ADMIN") || has(a, "STAFF_WAREHOUSE")
            ? " WHERE 1=1"
            : has(a, "STAFF_CASHIER") ? " WHERE MaThuNgan=?" : " WHERE MaNguoiGiaoHang=?";
    return where.contains("?")
        ? db.rows(
            OrderService.SUMMARY + where + " ORDER BY NgayTao DESC LIMIT ? OFFSET ?",
            a.getName(),
            size,
            Db.offset(page, size))
        : db.rows(
            OrderService.SUMMARY + where + " ORDER BY NgayTao DESC LIMIT ? OFFSET ?",
            size,
            Db.offset(page, size));
  }

  /** Chi tiết để in pick list/receipt; staff không được lấy đơn ngoài phạm vi. */
  @GetMapping("/staff/orders/{id}")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')")
  public Map<String, Object> staffOrder(Authentication a, @PathVariable String id) {
    var o = orders.detail(id);
    Problem.require(
        has(a, "SHOP_ADMIN")
            || has(a, "STAFF_WAREHOUSE")
            || a.getName().equals(o.get("shipperId"))
            || db.count(
                    "SELECT COUNT(*) FROM DonHang WHERE MaDonHang=? AND MaThuNgan=?",
                    id,
                    a.getName())
                > 0,
        404,
        "NOT_FOUND",
        "Không có đơn trong phạm vi của bạn.");
    return o;
  }

  /** Kho nhận đơn và chuyển sang đóng gói. */
  @PostMapping("/staff/orders/{id}/pack")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public Map<String, Object> pack(@PathVariable String id, Authentication a) {
    return orders.transition(id, a.getName(), "pack", null);
  }

  /** Kho phân công đúng nhân viên có role shipper. */
  @PostMapping("/staff/orders/{id}/assign")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public Map<String, Object> assign(
      @PathVariable String id, @Valid @RequestBody Assignment b, Authentication a) {
    return orders.transition(id, a.getName(), "assign", b.shipperId());
  }

  /** Shipper xác nhận đã giao và thu COD; không dùng cho thanh toán VietQR. */
  @PostMapping("/staff/orders/{id}/deliver")
  @PreAuthorize("hasRole('STAFF_SHIPPER')")
  public Map<String, Object> deliver(@PathVariable String id, Authentication a) {
    return orders.transition(id, a.getName(), "deliver", null);
  }

  /** Giao thất bại chuyển về kho chờ phân lại, chưa hoàn tồn khi hàng chưa về. */
  @PostMapping("/staff/orders/{id}/delivery-failed")
  @PreAuthorize("hasRole('STAFF_SHIPPER')")
  public Map<String, Object> failed(@PathVariable String id, Authentication a) {
    return orders.transition(id, a.getName(), "failed", null);
  }

  /** Kho xác nhận nhận lại hàng vật lý và hoàn tồn một lần. */
  @PostMapping("/staff/orders/{id}/receive-return")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public Map<String, Object> returned(@PathVariable String id, Authentication a) {
    return orders.cancel(id, a.getName(), false, true);
  }

  /** Admin hủy đơn chưa giao; không tự hoàn tiền khi chưa đối soát. */
  @PostMapping("/admin/orders/{id}/cancel")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> adminCancel(@PathVariable String id, Authentication a) {
    return orders.cancel(id, a.getName(), false, false);
  }

  /** Mở ca; khóa cashier để không có hai ca mở đồng thời. */
  @PostMapping("/pos/shifts")
  @Transactional
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public Map<String, Object> open(@Valid @RequestBody ShiftMoney b, Authentication a) {
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", a.getName());
    Problem.require(
        db.count(
                "SELECT COUNT(*) FROM CaLamViecThuNgan WHERE MaThuNgan=? AND TrangThaiCa='DANG_MO'",
                a.getName())
            == 0,
        409,
        "SHIFT_ALREADY_OPEN",
        "Bạn đã có ca đang mở.");
    String id = Db.id("CA");
    db.exec(
        "INSERT INTO CaLamViecThuNgan(MaCaLamViec,MaThuNgan,TienMatDauCa) VALUES(?,?,?)",
        id,
        a.getName(),
        b.amount());
    return Map.of("shiftId", id);
  }

  /** Đọc tổng doanh thu ca từ các đơn liên kết, không tin tổng client gửi lên. */
  @GetMapping("/pos/shifts/{id}")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public Map<String, Object> shift(@PathVariable String id, Authentication a) {
    var row =
        db.one(
            "SELECT MaCaLamViec AS shiftId,MaThuNgan AS cashierId,TienMatDauCa AS"
                + " openingCash,TienMatKiemDemThucTe AS countedCash,TrangThaiCa AS status FROM"
                + " CaLamViecThuNgan WHERE MaCaLamViec=?",
            id);
    Problem.require(
        has(a, "SHOP_ADMIN") || a.getName().equals(row.get("cashierId")),
        404,
        "NOT_FOUND",
        "Không có ca của bạn.");
    var total =
        db.one(
            "SELECT COALESCE(SUM(c.SoLuong*c.DonGiaLucDatHang),0) AS gross FROM ChiTietDonHang c"
                + " JOIN DonHang d ON d.MaDonHang=c.MaDonHang WHERE d.MaThuNgan=(SELECT MaThuNgan FROM CaLamViecThuNgan WHERE MaCaLamViec=?) AND d.KenhBanHang='TAI_QUAY_POS' AND d.PhuongThucThanhToan='TIEN_MAT_TAI_QUAY' AND d.NgayTao >= (SELECT ThoiGianMoCa FROM CaLamViecThuNgan WHERE MaCaLamViec=?) AND d.NgayTao < COALESCE((SELECT ThoiGianDongCa FROM CaLamViecThuNgan WHERE MaCaLamViec=?),CURRENT_TIMESTAMP(6)) AND"
                + " d.TrangThaiDonHang='HOAN_TAT'",
            id, id, id);
    BigDecimal discounts =
        Db.money(
            db.one(
                "SELECT COALESCE(SUM(TienGiamGia),0) AS amount FROM DonHang d WHERE d.MaThuNgan=(SELECT MaThuNgan FROM CaLamViecThuNgan WHERE MaCaLamViec=?) AND d.KenhBanHang='TAI_QUAY_POS' AND d.PhuongThucThanhToan='TIEN_MAT_TAI_QUAY' AND d.NgayTao >= (SELECT ThoiGianMoCa FROM CaLamViecThuNgan WHERE MaCaLamViec=?) AND d.NgayTao < COALESCE((SELECT ThoiGianDongCa FROM CaLamViecThuNgan WHERE MaCaLamViec=?),CURRENT_TIMESTAMP(6)) AND"
                    + " TrangThaiDonHang='HOAN_TAT'",
                id, id, id),
            "amount");
    row.put("cashSales", Db.money(total, "gross").subtract(discounts));
    row.put("expectedCash", Db.money(row, "openingCash").add((BigDecimal) row.get("cashSales")));
    return row;
  }

  /** Kết ca đúng người mở, lưu tiền kiểm đếm và chờ chủ shop duyệt. */
  @PostMapping("/pos/shifts/{id}/close")
  @Transactional
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  public Map<String, Object> close(
      @PathVariable String id, @Valid @RequestBody ShiftMoney b, Authentication a) {
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", a.getName());
    Problem.require(
        db.exec(
                "UPDATE CaLamViecThuNgan SET"
                    + " TrangThaiCa='DA_DONG_CHO_DUYET',ThoiGianDongCa=CURRENT_TIMESTAMP,TienMatKiemDemThucTe=?,GhiChuKetCa=?"
                    + " WHERE MaCaLamViec=? AND MaThuNgan=? AND TrangThaiCa='DANG_MO'",
                b.amount(),
                b.note(),
                id,
                a.getName())
            == 1,
        409,
        "SHIFT_STATE_INVALID",
        "Ca không mở hoặc không thuộc bạn.");
    return shift(id, a);
  }

  /** Duyệt bàn giao ca sau khi đối soát. */
  @PostMapping("/admin/shifts/{id}/approve")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> approve(@PathVariable String id, Authentication a) {
    Problem.require(
        db.exec(
                "UPDATE CaLamViecThuNgan SET TrangThaiCa='DA_PHE_DUYET_BAN_GIAO' WHERE"
                    + " MaCaLamViec=? AND TrangThaiCa='DA_DONG_CHO_DUYET'",
                id)
            == 1,
        409,
        "SHIFT_STATE_INVALID",
        "Ca chưa chờ duyệt.");
    db.audit(a.getName(), "SHIFT_APPROVE", id);
    return shift(id, a);
  }

  /** Bán tiền mặt tại quầy; JSON kết quả cung cấp dữ liệu hóa đơn cho FE in. */
  @PostMapping("/pos/orders")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> pos(
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody OrderController.Checkout b,
      Authentication a) {
    Problem.require(
        b.addressId() == null, 422, "POS_INVALID", "Đơn POS không dùng địa chỉ giao hàng.");
    return orders.create(a.getName(), key, b, true);
  }

  /** Tạo voucher, chặn phần trăm lớn hơn 100 và thời gian đảo ngược. */
  @PostMapping("/admin/promotions")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> promotion(@Valid @RequestBody Promotion b) {
    Problem.require(
        b.endsAt().isAfter(b.startsAt())
            && (!b.type().equals("PHAN_TRAM") || b.value().compareTo(BigDecimal.valueOf(100)) <= 0),
        422,
        "PROMOTION_INVALID",
        "Thời gian hoặc giá trị voucher không hợp lệ.");
    String id = Db.id("KM");
    db.exec(
        "INSERT INTO"
            + " KhuyenMai(MaKhuyenMai,MaPhieuGiamGia,TenChuongTrinh,LoaiGiamGia,GiaTriGiam,GiaTriDonHangToiThieu,GiamToiDa,SoLuongPhatHanh,NgayBatDau,NgayKetThuc)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        b.code(),
        b.name(),
        b.type(),
        b.value(),
        b.minimumTotal(),
        b.maxDiscount(),
        b.quantity(),
        java.sql.Timestamp.from(b.startsAt()),
        java.sql.Timestamp.from(b.endsAt()));
    return Map.of("promotionId", id);
  }

  /** Dừng voucher mà vẫn giữ lịch sử đơn đã áp dụng. */
  @DeleteMapping("/admin/promotions/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> stopPromotion(@PathVariable String id) {
    Problem.require(
        db.exec("UPDATE KhuyenMai SET TrangThai='TAM_DUNG' WHERE MaKhuyenMai=?", id) == 1,
        404,
        "NOT_FOUND",
        "Không có voucher.");
    return Map.of("paused", true);
  }

  /** Dashboard doanh thu snapshot của các đơn hoàn tất; chưa coi là báo cáo kế toán. */
  @GetMapping("/admin/reports/summary")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> report() {
    return Map.of(
        "orderCount",
        db.count("SELECT COUNT(*) FROM DonHang"),
        "completedCount",
        db.count("SELECT COUNT(*) FROM DonHang WHERE TrangThaiDonHang='HOAN_TAT'"),
        "activeProducts",
        db.count("SELECT COUNT(*) FROM SanPham WHERE TrangThaiSanPham='DANG_KINH_DOANH'"),
        "grossSales",
        db.one(
                "SELECT COALESCE(SUM(c.SoLuong*c.DonGiaLucDatHang),0) AS amount FROM ChiTietDonHang"
                    + " c JOIN DonHang d ON d.MaDonHang=c.MaDonHang WHERE"
                    + " d.TrangThaiDonHang='HOAN_TAT'")
            .get("amount"));
  }

  /** Đọc quyền từ Authentication đã được nạp lại từ DB. */
  private static boolean has(Authentication a, String role) {
    return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_" + role));
  }
}
