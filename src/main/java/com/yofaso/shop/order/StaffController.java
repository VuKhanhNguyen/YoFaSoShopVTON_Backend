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

  public record UpdateOrder(
      @Size(max = 150) String recipientName,
      @Size(max = 20) String phone,
      @Size(max = 500) String address,
      @Size(max = 1000) String note,
      @Pattern(regexp = "CHO_XAC_NHAN|DANG_DONG_GOI|DANG_GIAO_HANG|HOAN_TAT|DA_HUY|TRA_HANG") String status) {}

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

  /** Danh sách user cho quản trị: hỗ trợ lọc role (CUSTOMER / STAFF / ALL), kèm địa chỉ mặc định và phân quyền; không lộ password. */
  @GetMapping("/admin/users")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> users(
      @RequestParam(required = false) String role,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {
    String whereClause = "";
    if ("CUSTOMER".equalsIgnoreCase(role)) {
      whereClause = " WHERE EXISTS (SELECT 1 FROM NguoiDung_VaiTro nv JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro WHERE nv.MaNguoiDung=u.MaNguoiDung AND v.TenVaiTro='ROLE_CUSTOMER')";
    } else if ("STAFF".equalsIgnoreCase(role)) {
      whereClause = " WHERE EXISTS (SELECT 1 FROM NguoiDung_VaiTro nv JOIN VaiTro v ON v.MaVaiTro=nv.MaVaiTro WHERE nv.MaNguoiDung=u.MaNguoiDung AND (v.TenVaiTro LIKE 'ROLE_STAFF_%' OR v.TenVaiTro='ROLE_SHOP_ADMIN'))";
    }

    var list = db.rows(
        "SELECT u.MaNguoiDung AS userId, u.TenDangNhap AS username, u.HoVaTen AS fullName, "
            + "u.SoDienThoai AS phone, u.Email AS email, u.AnhDaiDien AS avatar, "
            + "u.DiemTichLuy AS points, u.HangThanhVien AS membership, u.NgaySinh AS dateOfBirth, "
            + "u.GioiTinh AS gender, u.TrangThaiHoatDong AS status, u.NgayTao AS createdAt, "
            + "dc.SoNhaTenDuong AS street, dc.PhuongXa AS ward, dc.TinhThanhPho AS city "
            + "FROM NguoiDung u "
            + "LEFT JOIN SoDiaChi dc ON dc.MaNguoiDung=u.MaNguoiDung AND dc.LaMacDinh=1"
            + whereClause
            + " ORDER BY u.MaNguoiDung LIMIT ? OFFSET ?",
        size,
        Db.offset(page, size));

    for (var u : list) {
      String uid = Db.text(u, "userId");
      var roles = db.rows(
          "SELECT v.MaVaiTro AS roleId, v.TenVaiTro AS name, v.TenHienThi AS label "
              + "FROM VaiTro v JOIN NguoiDung_VaiTro r ON r.MaVaiTro=v.MaVaiTro WHERE r.MaNguoiDung=?",
          uid);
      u.put("roles", roles);

      // Ghép chuỗi địa chỉ hoàn chỉnh từ SoNhaTenDuong, PhuongXa, TinhThanhPho
      String street = Db.text(u, "street");
      String ward = Db.text(u, "ward");
      String city = Db.text(u, "city");
      List<String> parts = new ArrayList<>();
      if (!street.isBlank()) parts.add(street);
      if (!ward.isBlank()) parts.add(ward);
      if (!city.isBlank()) parts.add(city);
      u.put("address", String.join(", ", parts));
    }
    return list;
  }

  /** Xóa hoặc vô hiệu hóa tài khoản nhân sự; bảo toàn dữ liệu nếu đã có lịch sử đơn/ca. */
  @DeleteMapping("/admin/users/{id}")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> deleteUser(@PathVariable String id, Authentication a) {
    Problem.require(
        !id.equals(a.getName()),
        409,
        "SELF_CHANGE_FORBIDDEN",
        "Không thể xóa tài khoản admin đang đăng nhập.");
    var user = db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=?", id);
    Problem.require(!user.isEmpty(), 404, "NOT_FOUND", "Không tìm thấy người dùng.");

    long ordersCount = db.count("SELECT COUNT(*) FROM DonHang WHERE MaThuNgan=? OR MaNguoiGiaoHang=? OR MaNguoiDung=?", id, id, id);
    long shiftsCount = db.count("SELECT COUNT(*) FROM CaLamViecThuNgan WHERE MaThuNgan=?", id);

    if (ordersCount > 0 || shiftsCount > 0) {
      db.exec("UPDATE NguoiDung SET TrangThaiHoatDong='KHOA_TAM_THOI' WHERE MaNguoiDung=?", id);
      db.runtime().bump(id);
      db.audit(a.getName(), "USER_LOCK_RETAIN_DATA", id);
      return Map.of("deleted", false, "locked", true, "message", "Tài khoản có dữ liệu lịch sử đơn hàng hoặc ca làm việc nên hệ thống đã tự động khóa bảo toàn.");
    }

    db.exec("DELETE FROM NguoiDung_VaiTro WHERE MaNguoiDung=?", id);
    db.exec("DELETE FROM SoDiaChi WHERE MaNguoiDung=?", id);
    db.exec("DELETE FROM NguoiDung_KhuyenMai WHERE MaNguoiDung=?", id);
    db.exec("DELETE FROM NguoiDung WHERE MaNguoiDung=?", id);
    db.runtime().bump(id);
    db.audit(a.getName(), "USER_DELETE", id);
    return Map.of("deleted", true);
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
    return db.one(
        "SELECT b.MaBienThe AS variantId,b.MaSanPham AS productId,b.MaQuanLyHangHoa AS sku,b.KichCo AS size,b.TenMau AS colorName,b.MaMauSac AS colors,b.GiaNiemYet AS price,b.GiaKhuyenMai AS salePrice,b.SoLuongTonKho AS stock,b.ViTriGiaKe AS shelf,b.TrangThai AS status,p.TenSanPham AS productName,p.TenSanPham AS name,p.MaDanhMuc AS categoryId FROM BienTheSanPham b JOIN SanPham p ON p.MaSanPham=b.MaSanPham WHERE b.MaQuanLyHangHoa=? OR b.MaBienThe=?",
        sku, sku);
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

  /** Chuẩn hóa tọa độ kệ từ CSDL sang đối tượng {day, ke, tang} cho UI kho điều phối. */
  public static Map<String, Object> parseShelf(String raw) {
    if (raw == null || raw.isBlank()) {
      return Map.of("day", "A", "ke", "KHO-01", "tang", 1);
    }
    String clean = raw.trim().toUpperCase(Locale.ROOT);
    String day = "A";
    int tang = 1;
    var m = java.util.regex.Pattern.compile("(?:KE-)?([A-Z])[-_]?(\\d+)?").matcher(clean);
    if (m.find()) {
      if (m.group(1) != null) day = m.group(1);
      if (m.group(2) != null) {
        try {
          tang = (Integer.parseInt(m.group(2)) % 4) + 1;
        } catch (Exception ignored) {}
      }
    }
    return Map.of("day", day, "ke", clean, "tang", tang);
  }

  /** Đơn staff: shipper thấy đơn được phân hoặc nhận thêm, kho thấy toàn bộ đơn kèm chi tiết dòng hàng & kệ. */
  @GetMapping("/staff/orders")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')")
  public List<Map<String, Object>> staffOrders(
      Authentication a,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {
    String where;
    List<Object> params = new ArrayList<>();
    if (has(a, "SHOP_ADMIN") || has(a, "STAFF_WAREHOUSE")) {
      if (status != null && !status.isBlank()) {
        where = " WHERE TrangThaiDonHang=?";
        params.add(status);
      } else {
        where = " WHERE 1=1";
      }
    } else if (has(a, "STAFF_CASHIER")) {
      where = " WHERE MaThuNgan=?";
      params.add(a.getName());
    } else {
      where = " WHERE MaNguoiGiaoHang=?";
      params.add(a.getName());
    }

    params.add(size);
    params.add(Db.offset(page, size));

    var list = db.rows(
        OrderService.SUMMARY + where + " ORDER BY NgayTao DESC LIMIT ? OFFSET ?",
        params.toArray());

    for (var r : list) {
      String id = Db.text(r, "orderId");
      r.put("id", id);
      r.put("receiver", r.get("recipientName"));
      r.put("phone", r.get("phone"));
      r.put("address", r.get("address"));
      r.put("placedAt", r.get("createdAt") != null ? r.get("createdAt").toString() : "");

      String pm = Db.text(r, "paymentMethod");
      r.put("payment", pm.contains("VIETQR") ? "VIETQR" : "COD");
      r.put("cod", r.get("collectAmount") != null ? ((Number) r.get("collectAmount")).doubleValue() : 0.0);

      String shipperId = (String) r.get("shipperId");
      if (shipperId != null && !shipperId.isBlank()) {
        var sh = db.one("SELECT HoVaTen FROM NguoiDung WHERE MaNguoiDung=?", shipperId);
        String name = Db.text(sh, "HoVaTen");
        r.put("shipper", name.isBlank() ? "Shipper " + shipperId : name);
        r.put("shipperName", name.isBlank() ? "Shipper " + shipperId : name);
      } else {
        r.put("shipper", "Chưa phân công");
        r.put("shipperName", "Chưa phân công");
      }

      var lines = db.rows(
          "SELECT c.MaChiTietDonHang AS lineId, c.MaBienThe AS variantId, "
              + "b.MaQuanLyHangHoa AS sku, b.MaQuanLyHangHoa AS barcode, "
              + "c.TenSanPhamLucDatHang AS ten, c.TenSanPhamLucDatHang AS name, "
              + "c.KichCoLucDatHang AS size, c.TenMauLucDatHang AS mau, c.TenMauLucDatHang AS colorName, "
              + "c.SoLuong AS qty, c.SoLuong AS quantity, c.DonGiaLucDatHang AS unitPrice, "
              + "b.ViTriGiaKe AS shelf "
              + "FROM ChiTietDonHang c "
              + "JOIN BienTheSanPham b ON b.MaBienThe = c.MaBienThe "
              + "WHERE c.MaDonHang=? ORDER BY b.ViTriGiaKe, b.MaQuanLyHangHoa",
          id);

      for (var l : lines) {
        String shelfStr = (String) l.get("shelf");
        l.put("shelf", parseShelf(shelfStr));
      }

      r.put("lines", lines);
      r.put("items", lines);
    }
    return list;
  }

  /** Danh sách nhân viên giao hàng đang hoạt động để kho bàn giao kiện hàng. */
  @GetMapping("/staff/shippers")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public List<Map<String, Object>> shippers() {
    return db.rows(
        "SELECT u.MaNguoiDung AS id, u.HoVaTen AS name, u.SoDienThoai AS phone, "
            + "COALESCE(CONCAT('Tuyến ', dc.PhuongXa, ', ', dc.TinhThanhPho), 'Tuyến Quận Hải Châu, Đà Nẵng') AS route "
            + "FROM NguoiDung u "
            + "JOIN NguoiDung_VaiTro nv ON nv.MaNguoiDung = u.MaNguoiDung "
            + "JOIN VaiTro v ON v.MaVaiTro = nv.MaVaiTro "
            + "LEFT JOIN SoDiaChi dc ON dc.MaNguoiDung = u.MaNguoiDung AND dc.LaMacDinh = 1 "
            + "WHERE v.TenVaiTro = 'ROLE_STAFF_SHIPPER' AND u.TrangThaiHoatDong = 'HOAT_DONG' "
            + "ORDER BY u.MaNguoiDung");
  }

  /** Chi tiết để in pick list/receipt; staff không được lấy đơn ngoài phạm vi. Cho phép tìm theo mã đơn hoặc mã SKU/Barcode. */
  @GetMapping("/staff/orders/{id}")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')")
  public Map<String, Object> staffOrder(Authentication a, @PathVariable String id) {
    String clean = id.trim().toUpperCase().replaceFirst("^#", "").replaceFirst("^YFS", "");
    String target = clean;
    if (clean.matches("^\\d+$")) {
      target = String.format("DH%08d", Long.parseLong(clean));
    }

    var check = db.rows("SELECT MaDonHang FROM DonHang WHERE MaDonHang=?", target);
    if (check.isEmpty()) {
      var byEnd = db.rows("SELECT MaDonHang FROM DonHang WHERE MaDonHang LIKE ? ORDER BY NgayTao DESC LIMIT 1", "%" + clean);
      if (!byEnd.isEmpty()) {
        target = Db.text(byEnd.get(0), "MaDonHang");
      } else {
        var bySku = db.rows(
            "SELECT d.MaDonHang FROM DonHang d "
                + "JOIN ChiTietDonHang c ON c.MaDonHang = d.MaDonHang "
                + "JOIN BienTheSanPham b ON b.MaBienThe = c.MaBienThe "
                + "WHERE UPPER(b.MaQuanLyHangHoa) = ? OR UPPER(b.MaVach) = ? "
                + "ORDER BY CASE WHEN d.TrangThaiDonHang IN ('TRA_HANG', 'DANG_GIAO_HANG', 'CHO_SHIPPER') THEN 1 ELSE 2 END, d.NgayTao DESC LIMIT 1",
            clean, clean);
        if (!bySku.isEmpty()) {
          target = Db.text(bySku.get(0), "MaDonHang");
        }
      }
    }

    var o = orders.detail(target);
    Problem.require(
        has(a, "SHOP_ADMIN")
            || has(a, "STAFF_WAREHOUSE")
            || a.getName().equals(o.get("shipperId"))
            || db.count(
                    "SELECT COUNT(*) FROM DonHang WHERE MaDonHang=? AND MaThuNgan=?",
                    target,
                    a.getName())
                > 0,
        404,
        "NOT_FOUND",
        "Không có đơn trong phạm vi của bạn.");

    o.put("id", target);
    o.put("receiver", o.get("recipientName"));
    o.put("phone", o.get("phone"));
    o.put("address", o.get("address"));

    var lines = db.rows(
        "SELECT c.MaChiTietDonHang AS lineId, c.MaBienThe AS variantId, "
            + "b.MaQuanLyHangHoa AS sku, b.MaQuanLyHangHoa AS barcode, "
            + "c.TenSanPhamLucDatHang AS ten, c.TenSanPhamLucDatHang AS name, "
            + "c.KichCoLucDatHang AS size, c.TenMauLucDatHang AS mau, c.TenMauLucDatHang AS colorName, "
            + "c.SoLuong AS qty, c.SoLuong AS quantity, c.DonGiaLucDatHang AS unitPrice, "
            + "b.ViTriGiaKe AS shelf "
            + "FROM ChiTietDonHang c "
            + "JOIN BienTheSanPham b ON b.MaBienThe = c.MaBienThe "
            + "WHERE c.MaDonHang=? ORDER BY b.ViTriGiaKe, b.MaQuanLyHangHoa",
        target);

    for (var l : lines) {
      l.put("shelf", parseShelf((String) l.get("shelf")));
    }
    o.put("lines", lines);
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
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')")
  public Map<String, Object> deliver(
      @PathVariable String id,
      @RequestBody(required = false) Map<String, Object> body,
      Authentication a) {
    return orders.transition(id, a.getName(), "deliver", null);
  }

  /** Giao thất bại chuyển về kho chờ phân lại hoặc cập nhật số lần giao và lý do. */
  @PostMapping("/staff/orders/{id}/delivery-failed")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')")
  public Map<String, Object> failed(
      @PathVariable String id,
      @RequestBody(required = false) Map<String, String> body,
      Authentication a) {
    String reason = body != null ? body.getOrDefault("reason", "KHONG_NGHE_MAY") : "KHONG_NGHE_MAY";
    String note = body != null ? body.getOrDefault("note", "") : "";
    return orders.deliveryFailed(id, a.getName(), reason, note);
  }

  /** Kiện hàng đã đóng gói đang chờ bàn giao cho shipper tiếp nhận thêm. */
  @GetMapping("/staff/shipper/pending-parcels")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')")
  public List<Map<String, Object>> pendingParcels() {
    return db.rows(
        "SELECT MaDonHang AS maDon, TenNguoiNhan AS khach, SoDienThoaiNhan AS sdt, "
            + "DiaChiGiaoChiTiet AS diaChi, SoTienThuHo AS soTienThuHo, "
            + "COALESCE(GhiChuDonHang, 'Cho xem hàng, không cho thử') AS ghiChu, "
            + "SoLanGiao AS soLanGiao, 'DANG_GIAO_HANG' AS status "
            + "FROM DonHang "
            + "WHERE KenhBanHang='ONLINE_APP' AND TrangThaiDonHang IN ('DANG_DONG_GOI', 'CHO_XAC_NHAN') AND MaNguoiGiaoHang IS NULL "
            + "ORDER BY NgayTao DESC LIMIT 50");
  }

  /** Shipper quét nhận thêm kiện hàng đóng gói vào lộ trình giao cá nhân. */
  @PostMapping("/staff/orders/{id}/pickup")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')")
  public Map<String, Object> pickup(@PathVariable String id, Authentication a) {
    var o = db.one("SELECT * FROM DonHang WHERE MaDonHang=? FOR UPDATE", id);
    Problem.require(!o.isEmpty(), 404, "NOT_FOUND", "Không tìm thấy đơn hàng.");
    String state = Db.text(o, "TrangThaiDonHang");
    Problem.require(
        Set.of("CHO_XAC_NHAN", "DANG_DONG_GOI").contains(state),
        409,
        "ORDER_STATE_INVALID",
        "Đơn hàng không ở trạng thái chờ giao.");
    db.exec(
        "UPDATE DonHang SET MaNguoiGiaoHang=?, TrangThaiDonHang='DANG_GIAO_HANG', SoLanGiao=SoLanGiao+1 WHERE MaDonHang=?",
        a.getName(),
        id);
    db.audit(a.getName(), "ORDER_SHIPPER_PICKUP", id);
    return orders.detail(id);
  }

  /** Quyết toán ca giao hàng cuối ngày của shipper nộp tiền COD cho thu ngân. */
  @PostMapping("/staff/shipper/settle")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')")
  public Map<String, Object> settle(Authentication a) {
    db.audit(a.getName(), "SHIPPER_SETTLE", a.getName());
    return Map.of("settled", true, "timestamp", java.time.Instant.now().toString());
  }

  /** Xác nhận đổi trả/nhận lại hàng tại quầy hoặc kho và hoàn tồn một lần hoặc chuyển kho lỗi. */
  @PostMapping("/staff/orders/{id}/receive-return")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER')")
  public Map<String, Object> returned(
      @PathVariable String id,
      @RequestParam(defaultValue = "true") boolean restock,
      Authentication a) {
    return orders.cancel(id, a.getName(), false, true, restock);
  }

  /** Danh mục kiểm kê theo dãy kệ thực tế trong kho từ bảng BienTheSanPham & SanPham. */
  @GetMapping("/staff/inventory/audit-zones")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public Map<String, Object> auditZones() {
    var rows = db.rows(
        "SELECT b.MaBienThe AS variantId, b.MaQuanLyHangHoa AS sku, p.TenSanPham AS ten, "
            + "b.KichCo AS size, b.TenMau AS mau, b.SoLuongTonKho AS book, "
            + "COALESCE(b.ViTriGiaKe, 'KHO-01') AS ke "
            + "FROM BienTheSanPham b "
            + "JOIN SanPham p ON p.MaSanPham = b.MaSanPham "
            + "ORDER BY b.ViTriGiaKe, b.MaQuanLyHangHoa");

    Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
    for (var r : rows) {
      String ke = Db.text(r, "ke");
      String zoneKey = "A";
      var m = java.util.regex.Pattern.compile("(?:KE-)?([A-Z])").matcher(ke.toUpperCase(Locale.ROOT));
      if (m.find() && m.group(1) != null) {
        zoneKey = m.group(1);
      }
      grouped.computeIfAbsent(zoneKey, k -> new ArrayList<>()).add(r);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    for (var e : grouped.entrySet()) {
      String key = e.getKey();
      String label = switch (key) {
        case "F" -> "DÃY KỆ F (THỜI TRANG NỮ / ÁO TOPS & VÁY)";
        case "M" -> "DÃY KỆ M (THỜI TRANG NAM / SƠ MI & QUẦN)";
        case "B" -> "DÃY KỆ B (QUẦN BOTTOMS)";
        default -> "DÃY KỆ " + key + " (KHO HÀNG TRUNG TÂM)";
      };
      result.put(key, Map.of("label", label, "items", e.getValue()));
    }
    return result;
  }

  /** Báo cáo Xuất - Nhập - Tồn từ BienTheSanPham và BienDongKho. */
  @GetMapping("/staff/inventory/summary")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public Map<String, Object> inventorySummary() {
    long tonCuoi = db.count("SELECT COALESCE(SUM(SoLuongTonKho), 0) FROM BienTheSanPham");
    long nhapMoi = db.count("SELECT COALESCE(SUM(SoLuongThayDoi), 0) FROM BienDongKho WHERE SoLuongThayDoi > 0");
    long xuatBan = db.count("SELECT COALESCE(SUM(ABS(SoLuongThayDoi)), 0) FROM BienDongKho WHERE SoLuongThayDoi < 0");
    long tonDau = Math.max(0, tonCuoi - nhapMoi + xuatBan);
    if (tonDau == 0 && tonCuoi > 0) tonDau = tonCuoi;
    return Map.of(
        "tonDau", tonDau,
        "nhapMoi", nhapMoi,
        "xuatBan", xuatBan,
        "tonCuoi", tonCuoi);
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

  /** Danh sách toàn bộ chương trình khuyến mãi cho chủ shop quản lý đa kênh. */
  @GetMapping("/admin/promotions")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> adminPromotions() {
    return db.rows(
        "SELECT MaKhuyenMai AS promotionId, MaPhieuGiamGia AS code, TenChuongTrinh AS name, "
            + "LoaiGiamGia AS type, GiaTriGiam AS value, GiaTriDonHangToiThieu AS minimumTotal, "
            + "GiamToiDa AS maxDiscount, SoLuongPhatHanh AS quantity, SoLuongDaDung AS used, "
            + "NgayBatDau AS startsAt, NgayKetThuc AS expiresAt, TrangThai AS status "
            + "FROM KhuyenMai ORDER BY NgayBatDau DESC, MaKhuyenMai DESC");
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

  /** Cập nhật thông tin giao hàng/người nhận/ghi chú đơn hàng cho admin. */
  @PatchMapping("/admin/orders/{id}")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> updateOrder(
      @PathVariable String id, @Valid @RequestBody UpdateOrder b, Authentication a) {
    var order = db.one("SELECT MaDonHang, TrangThaiDonHang FROM DonHang WHERE MaDonHang=?", id);
    Problem.require(!order.isEmpty(), 404, "NOT_FOUND", "Không tìm thấy đơn hàng.");

    List<String> updates = new ArrayList<>();
    List<Object> params = new ArrayList<>();

    if (b.recipientName() != null && !b.recipientName().isBlank()) {
      updates.add("TenNguoiNhan=?");
      params.add(b.recipientName());
    }
    if (b.phone() != null && !b.phone().isBlank()) {
      updates.add("SoDienThoaiNguoiNhan=?");
      params.add(b.phone());
    }
    if (b.address() != null && !b.address().isBlank()) {
      updates.add("DiaChiGiaoHang=?");
      params.add(b.address());
    }
    if (b.note() != null) {
      updates.add("GhiChuDonHang=?");
      params.add(b.note());
    }
    if (b.status() != null && !b.status().isBlank()) {
      updates.add("TrangThaiDonHang=?");
      params.add(b.status());
    }

    if (!updates.isEmpty()) {
      params.add(id);
      db.exec("UPDATE DonHang SET " + String.join(", ", updates) + " WHERE MaDonHang=?", params.toArray());
      if ("HOAN_TAT".equals(b.status())) {
        db.exec("UPDATE NguoiDung_KhuyenMai SET TrangThai='DA_DUNG' WHERE MaDonHangDaDung=?", id);
      } else if ("DA_HUY".equals(b.status()) || "TRA_HANG".equals(b.status())) {
        db.exec("UPDATE NguoiDung_KhuyenMai SET TrangThai='CHUA_DUNG', MaDonHangDaDung=NULL, NgaySuDung=NULL WHERE MaDonHangDaDung=?", id);
      }
      db.audit(a.getName(), "ORDER_UPDATE", id);
    }

    return Map.of("updated", true, "orderId", id);
  }

  /** Danh sách ca làm việc thu ngân POS cho chủ shop theo dõi và duyệt bàn giao két. */
  @GetMapping("/admin/shifts")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> adminShifts(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return db.rows(
        "SELECT c.MaCaLamViec AS shiftId, c.MaThuNgan AS cashierId, "
            + "COALESCE(u.HoVaTen, u.TenDangNhap) AS cashierName, "
            + "c.ThoiGianMoCa AS openedAt, c.ThoiGianDongCa AS closedAt, "
            + "c.TienMatDauCa AS openingCash, c.TienMatKiemDemThucTe AS countedCash, "
            + "c.TrangThaiCa AS status, c.GhiChuKetCa AS note "
            + "FROM CaLamViecThuNgan c "
            + "LEFT JOIN NguoiDung u ON u.MaNguoiDung = c.MaThuNgan "
            + "ORDER BY c.ThoiGianMoCa DESC LIMIT ? OFFSET ?",
        size,
        Db.offset(page, size));
  }

  /** Yêu cầu đếm lại / từ chối duyệt ca. */
  @PostMapping("/admin/shifts/{id}/reject")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> rejectShift(@PathVariable String id, Authentication a) {
    Problem.require(
        db.exec(
                "UPDATE CaLamViecThuNgan SET TrangThaiCa='DANG_MO' WHERE"
                    + " MaCaLamViec=? AND TrangThaiCa='DA_DONG_CHO_DUYET'",
                id)
            == 1,
        409,
        "SHIFT_STATE_INVALID",
        "Ca không ở trạng thái chờ duyệt.");
    db.audit(a.getName(), "SHIFT_REJECT", id);
    return Map.of("rejected", true);
  }

  /** Danh sách biến động kho toàn hệ thống cho chủ shop kiểm tra và đối soát. */
  @GetMapping("/admin/inventory/movements")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> adminMovements(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return db.rows(
        "SELECT b.MaBienDong AS movementId, b.MaBienThe AS variantId, "
            + "COALESCE(s.TenSanPham, 'Sản phẩm') AS productName, "
            + "bt.KichCo AS size, bt.TenMau AS color, "
            + "b.LoaiBienDong AS type, b.SoLuongThayDoi AS delta, "
            + "b.SoLuongTonTruoc AS beforeStock, b.SoLuongTonSau AS afterStock, "
            + "b.MaThamChieu AS referenceId, b.GhiChu AS reason, "
            + "b.MaNhanVienThucHien AS performerId, COALESCE(u.HoVaTen, b.MaNhanVienThucHien) AS performerName, "
            + "b.NgayThucHien AS createdAt "
            + "FROM BienDongKho b "
            + "LEFT JOIN BienTheSanPham bt ON bt.MaBienThe = b.MaBienThe "
            + "LEFT JOIN SanPham s ON s.MaSanPham = bt.MaSanPham "
            + "LEFT JOIN NguoiDung u ON u.MaNguoiDung = b.MaNhanVienThucHien "
            + "ORDER BY b.NgayThucHien DESC LIMIT ? OFFSET ?",
        size,
        Db.offset(page, size));
  }

  /** Nhật ký kiểm toán hành vi trọng yếu toàn hệ thống từ các bảng CSDL MySQL (BienDongKho, CaLamViecThuNgan, DonHang). */
  @GetMapping("/admin/audit-logs")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> adminAuditLogs(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {
    return db.rows(
        "SELECT log.id, log.time, log.actor, log.action, log.reason "
            + "FROM ("
            + "  SELECT b.MaBienDong AS id, "
            + "         DATE_FORMAT(b.NgayThucHien, '%d/%m/%Y %H:%i') AS time, "
            + "         CONCAT(COALESCE(u.HoVaTen, u.TenDangNhap, 'Thủ kho'), ' · ', COALESCE(b.MaNhanVienThucHien, 'Hệ thống')) AS actor, "
            + "         CONCAT('Biến động kho [', b.LoaiBienDong, ']: ', (CASE WHEN b.SoLuongThayDoi > 0 THEN CONCAT('+', b.SoLuongThayDoi) ELSE CAST(b.SoLuongThayDoi AS CHAR) END), ' cái') AS action, "
            + "         CONCAT('Mã SKU: ', COALESCE(bt.MaQuanLyHangHoa, b.MaBienThe), ' · Lý do: ', COALESCE(b.GhiChu, 'Cân bằng tồn kho')) AS reason, "
            + "         b.NgayThucHien AS sortTime "
            + "  FROM BienDongKho b "
            + "  LEFT JOIN NguoiDung u ON u.MaNguoiDung = b.MaNhanVienThucHien "
            + "  LEFT JOIN BienTheSanPham bt ON bt.MaBienThe = b.MaBienThe "
            + "  UNION ALL "
            + "  SELECT c.MaCaLamViec AS id, "
            + "         DATE_FORMAT(COALESCE(c.ThoiGianDongCa, c.ThoiGianMoCa), '%d/%m/%Y %H:%i') AS time, "
            + "         CONCAT(COALESCE(u.HoVaTen, u.TenDangNhap, 'Thu ngân'), ' · ', c.MaThuNgan) AS actor, "
            + "         CONCAT('Quản lý ca POS [', c.TrangThaiCa, ']') AS action, "
            + "         CONCAT('Đầu ca: ', FORMAT(c.TienMatDauCa, 0), 'đ · Thực tế đếm: ', COALESCE(CONCAT(FORMAT(c.TienMatKiemDemThucTe, 0), 'đ'), 'Chưa kết ca'), ' · ', COALESCE(c.GhiChuKetCa, '')) AS reason, "
            + "         COALESCE(c.ThoiGianDongCa, c.ThoiGianMoCa) AS sortTime "
            + "  FROM CaLamViecThuNgan c "
            + "  LEFT JOIN NguoiDung u ON u.MaNguoiDung = c.MaThuNgan "
            + "  UNION ALL "
            + "  SELECT d.MaDonHang AS id, "
            + "         DATE_FORMAT(d.NgayCapNhat, '%d/%m/%Y %H:%i') AS time, "
            + "         CONCAT(COALESCE(u.HoVaTen, 'Khách hàng / Hệ thống'), ' · ', COALESCE(d.MaThuNgan, d.MaNguoiDung, 'Hệ thống')) AS actor, "
            + "         CONCAT('Cập nhật đơn hàng [', d.TrangThaiDonHang, ']') AS action, "
            + "         CONCAT('Kênh: ', d.KenhBanHang, ' · Phương thức: ', d.PhuongThucThanhToan, ' · ', COALESCE(d.GhiChuDonHang, '')) AS reason, "
            + "         d.NgayCapNhat AS sortTime "
            + "  FROM DonHang d "
            + "  LEFT JOIN NguoiDung u ON u.MaNguoiDung = COALESCE(d.MaThuNgan, d.MaNguoiDung) "
            + "  WHERE d.TrangThaiDonHang IN ('DA_HUY', 'TRA_HANG', 'HOAN_TAT') "
            + ") AS log "
            + "ORDER BY log.sortTime DESC LIMIT ? OFFSET ?",
        size,
        Db.offset(page, size));
  }

  /** Thống kê doanh thu theo ngày và kênh bán hàng từ bảng DonHang. */
  @GetMapping("/admin/reports/finance")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> financeReport() {
    List<Map<String, Object>> daily =
        db.rows(
            "SELECT DATE_FORMAT(d.NgayTao, '%Y-%m-%d') AS day, "
                + "COALESCE(SUM(CASE WHEN d.KenhBanHang='ONLINE_APP' THEN (COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia) ELSE 0 END), 0) AS online, "
                + "COALESCE(SUM(CASE WHEN d.KenhBanHang='TAI_QUAY_POS' THEN (COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia) ELSE 0 END), 0) AS pos, "
                + "COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total, "
                + "COUNT(d.MaDonHang) AS orderCount "
                + "FROM DonHang d "
                + "LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' "
                + "GROUP BY DATE_FORMAT(d.NgayTao, '%Y-%m-%d') "
                + "ORDER BY day ASC");

    List<Map<String, Object>> hourly =
        db.rows(
            "SELECT DATE_FORMAT(d.NgayTao, '%H') AS hour, "
                + "COALESCE(SUM(CASE WHEN d.KenhBanHang='ONLINE_APP' THEN (COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia) ELSE 0 END), 0) AS online, "
                + "COALESCE(SUM(CASE WHEN d.KenhBanHang='TAI_QUAY_POS' THEN (COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia) ELSE 0 END), 0) AS pos, "
                + "COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total, "
                + "COUNT(d.MaDonHang) AS orderCount "
                + "FROM DonHang d "
                + "LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' "
                + "AND DATE(d.NgayTao) = CURRENT_DATE() "
                + "GROUP BY DATE_FORMAT(d.NgayTao, '%H') "
                + "ORDER BY hour ASC");

    List<Map<String, Object>> monthly =
        db.rows(
            "SELECT DATE_FORMAT(d.NgayTao, '%Y-%m') AS month, "
                + "DATE_FORMAT(d.NgayTao, 'Tháng %m/%Y') AS label, "
                + "COALESCE(SUM(CASE WHEN d.KenhBanHang='ONLINE_APP' THEN (COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia) ELSE 0 END), 0) AS online, "
                + "COALESCE(SUM(CASE WHEN d.KenhBanHang='TAI_QUAY_POS' THEN (COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia) ELSE 0 END), 0) AS pos, "
                + "COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total, "
                + "COUNT(d.MaDonHang) AS orderCount "
                + "FROM DonHang d "
                + "LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' "
                + "GROUP BY DATE_FORMAT(d.NgayTao, '%Y-%m'), DATE_FORMAT(d.NgayTao, 'Tháng %m/%Y') "
                + "ORDER BY month ASC");

    List<Map<String, Object>> payments =
        db.rows(
            "SELECT d.PhuongThucThanhToan AS method, "
                + "COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total, "
                + "COUNT(d.MaDonHang) AS orderCount "
                + "FROM DonHang d "
                + "LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' "
                + "GROUP BY d.PhuongThucThanhToan");

    List<Map<String, Object>> customers =
        db.rows(
            "SELECT COALESCE(u.HangThanhVien, 'VANG_LAI') AS tier, "
                + "COUNT(DISTINCT d.MaDonHang) AS orderCount, "
                + "COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS totalRevenue "
                + "FROM DonHang d "
                + "LEFT JOIN NguoiDung u ON u.MaNguoiDung = d.MaNguoiDung "
                + "LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' "
                + "GROUP BY COALESCE(u.HangThanhVien, 'VANG_LAI')");

    long totalOrders = db.count("SELECT COUNT(*) FROM DonHang");
    long returnedOrders = db.count("SELECT COUNT(*) FROM DonHang WHERE TrangThaiDonHang='TRA_HANG'");
    long completedOrders = db.count("SELECT COUNT(*) FROM DonHang WHERE TrangThaiDonHang='HOAN_TAT'");
    double returnRate =
        totalOrders > 0
            ? Math.round(((double) returnedOrders / totalOrders) * 1000.0) / 10.0
            : 0.0;

    var qrExpected =
        db.one(
            "SELECT COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total "
                + "FROM DonHang d LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' AND d.PhuongThucThanhToan IN ('VIETQR_CHUYEN_KHOAN_ONLINE', 'VIETQR_TAI_QUAY')");
    var qrActual =
        db.one(
            "SELECT COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total "
                + "FROM DonHang d LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' AND d.PhuongThucThanhToan IN ('VIETQR_CHUYEN_KHOAN_ONLINE', 'VIETQR_TAI_QUAY') AND d.TrangThaiThanhToan='DA_THANH_TOAN'");

    var cashExpected =
        db.one(
            "SELECT COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total "
                + "FROM DonHang d LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' AND d.PhuongThucThanhToan='TIEN_MAT_TAI_QUAY'");
    var cashActual =
        db.one(
            "SELECT COALESCE((SELECT SUM(TienMatKiemDemThucTe) FROM CaLamViecThuNgan WHERE TienMatKiemDemThucTe IS NOT NULL), "
                + "(SELECT COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) FROM DonHang d LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang WHERE d.TrangThaiDonHang='HOAN_TAT' AND d.PhuongThucThanhToan='TIEN_MAT_TAI_QUAY')) AS total");

    var codExpected =
        db.one(
            "SELECT COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total "
                + "FROM DonHang d LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' AND d.PhuongThucThanhToan='COD_TIEN_MAT_KHI_NHAN'");
    var codActual =
        db.one(
            "SELECT COALESCE(SUM(COALESCE(ct.subtotal, 0) + d.PhiVanChuyen - d.TienGiamGia), 0) AS total "
                + "FROM DonHang d LEFT JOIN (SELECT MaDonHang, SUM(SoLuong * DonGiaLucDatHang) AS subtotal FROM ChiTietDonHang GROUP BY MaDonHang) ct ON ct.MaDonHang = d.MaDonHang "
                + "WHERE d.TrangThaiDonHang='HOAN_TAT' AND d.PhuongThucThanhToan='COD_TIEN_MAT_KHI_NHAN' AND d.TrangThaiThanhToan='DA_THANH_TOAN'");

    List<Map<String, Object>> flows = new ArrayList<>();
    flows.add(
        Map.of(
            "id", "qr",
            "label", "VietQR Online & Tại quầy",
            "source", "Sao kê ngân hàng",
            "expected", qrExpected.getOrDefault("total", 0),
            "actual", qrActual.getOrDefault("total", 0)));
    flows.add(
        Map.of(
            "id", "cash",
            "label", "Tiền mặt két POS",
            "source", "Biên bản kết ca thu ngân",
            "expected", cashExpected.getOrDefault("total", 0),
            "actual", cashActual.getOrDefault("total", 0)));
    flows.add(
        Map.of(
            "id", "cod",
            "label", "Tiền COD Shipper nộp",
            "source", "Đơn hàng xuất kho & biên nhận",
            "expected", codExpected.getOrDefault("total", 0),
            "actual", codActual.getOrDefault("total", 0)));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("daily", daily);
    result.put("hourly", hourly);
    result.put("monthly", monthly);
    result.put("payments", payments);
    result.put("customers", customers);
    result.put("flows", flows);
    result.put("returnRate", returnRate);
    result.put("totalOrders", totalOrders);
    result.put("completedOrders", completedOrders);
    result.put("returnedOrders", returnedOrders);
    return result;
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
