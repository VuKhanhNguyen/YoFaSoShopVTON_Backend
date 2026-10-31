package com.yofaso.shop.auth;

import com.yofaso.shop.common.*;
import com.yofaso.shop.location.LocationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Đăng nhập, hồ sơ và sổ địa chỉ của chính người đang đăng nhập. */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
  public record Register(
      @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{3,15}") String username,
      @NotBlank @Size(min = 10, max = 72) String password,
      @Size(max = 100) String fullName,
      @Pattern(regexp = "[0-9+]{9,15}") @NotNull String phone,
      @NotBlank @Email @Size(max = 100) String email) {}

  public record Login(
      @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{3,15}") String username, @NotBlank @Size(min = 10, max = 72) String password) {}

  public record Token(@NotBlank @Size(max = 100) String refreshToken) {}

  public record Profile(
      @NotBlank @Size(max = 100) String fullName,
      @Email @Size(max = 100) String email,
      @Pattern(regexp = "[0-9+]{9,15}") String phone,
      @Size(max=72) String currentPassword) {}

  public record Password(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Size(min = 10, max = 72) String newPassword) {}

  public record Address(
      @NotBlank @Size(max = 100) String recipientName,
      @Pattern(regexp = "[0-9+]{9,15}") @NotNull String phone,
      @NotBlank @Size(max = 255) String street,
      @NotBlank @Size(max = 100) String ward,
      @NotBlank @Size(max = 100) String city,
      boolean defaultAddress,
      @Pattern(regexp = "[0-9]{2}") String provinceCode,
      @Pattern(regexp = "[0-9]{5}") String wardCode) {}

  private final AuthService auth;
  private final Db db;
  private final LocationService locations;

  /** Nhận service tài khoản và kho dữ liệu địa chỉ. */
  public AuthController(AuthService auth, Db db, LocationService locations) {
    this.auth = auth;
    this.db = db;
    this.locations = locations;
  }

  /** Tạo tài khoản khách hàng, trả token để dùng ngay. */
  @PostMapping("/auth/register")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> register(@Valid @RequestBody Register b) {
    return auth.register(b);
  }

  /** Đăng nhập cho cả khách hàng và nhân sự shop. */
  @PostMapping("/auth/login")
  public Map<String, Object> login(@Valid @RequestBody Login b) {
    return auth.login(b.username(), b.password());
  }

  /** Đổi refresh token còn hạn lấy cặp token mới. */
  @PostMapping("/auth/refresh")
  public Map<String, Object> refresh(@Valid @RequestBody Token b) {
    return auth.refresh(b.refreshToken());
  }

  /** Thu hồi refresh; access hiện tại còn hiệu lực tối đa 15 phút. */
  @PostMapping("/auth/logout")
  public Map<String, Object> logout(Authentication a, @Valid @RequestBody Token b) {
    auth.logout(a.getName(), b.refreshToken());
    return Map.of("revoked", true);
  }

  /** Hồ sơ an toàn và quyền hiện hành. */
  @GetMapping("/me")
  public Map<String, Object> me(Authentication a) {
    return auth.me(a.getName());
  }

  /** Cập nhật tên/email của mình; không có field đổi role. */
  @PatchMapping("/me")
  @Transactional
  public Map<String, Object> profile(Authentication a, @Valid @RequestBody Profile b) {
    String email=AccountEmail.reserve(db,b.email(),a.getName());
    String previous=Db.text(db.one("SELECT Email FROM NguoiDung WHERE MaNguoiDung=?",a.getName()),"Email");
    if(email!=null && !email.equalsIgnoreCase(previous.trim())) auth.confirmPassword(a.getName(),b.currentPassword());
    db.exec(
        "UPDATE NguoiDung SET HoVaTen=?,Email=COALESCE(?,Email),SoDienThoai=COALESCE(?,SoDienThoai) WHERE"
            + " MaNguoiDung=?",
        b.fullName(),
        email,
        b.phone(),
        a.getName());
    return auth.me(a.getName());
  }

  /** Đổi mật khẩu và kết thúc các phiên cũ. */
  @PostMapping("/me/password")
  public Map<String, Object> password(Authentication a, @Valid @RequestBody Password b) {
    auth.changePassword(a.getName(), b.currentPassword(), b.newPassword());
    return Map.of("loginAgain", true);
  }

  /** Sổ địa chỉ riêng; không nhận userId từ client. */
  @GetMapping("/me/addresses")
  public List<Map<String, Object>> addresses(Authentication a) {
    return db.rows(
        "SELECT MaDiaChi AS addressId,TenNguoiNhan AS recipientName,SoDienThoaiNhan AS"
            + " phone,SoNhaTenDuong AS street,PhuongXa AS ward,TinhThanhPho AS city,LaMacDinh AS"
            + " defaultAddress FROM SoDiaChi WHERE MaNguoiDung=?",
        a.getName());
  }

  /** Thêm địa chỉ; khóa user để chỉ giữ một mặc định. */
  @PostMapping("/me/addresses")
  @Transactional
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> address(Authentication a, @Valid @RequestBody Address b) {
    var address = addressText(b);
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", a.getName());
    if (b.defaultAddress())
      db.exec("UPDATE SoDiaChi SET LaMacDinh=0 WHERE MaNguoiDung=?", a.getName());
    String id = Db.id("DC");
    db.exec(
        "INSERT INTO"
            + " SoDiaChi(MaDiaChi,MaNguoiDung,TenNguoiNhan,SoDienThoaiNhan,SoNhaTenDuong,PhuongXa,TinhThanhPho,LaMacDinh)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        a.getName(),
        b.recipientName(),
        b.phone(),
        b.street().strip(),
        address.ward(),
        address.city(),
        b.defaultAddress());
    return Map.of("addressId", id);
  }

  /** Sửa địa chỉ của chính mình, giữ quan hệ địa chỉ đã dùng bởi đơn. */
  @PutMapping("/me/addresses/{id}")
  @Transactional
  public Map<String, Object> updateAddress(
      Authentication a, @PathVariable String id, @Valid @RequestBody Address b) {
    var address = addressText(b);
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", a.getName());
    db.one("SELECT MaDiaChi FROM SoDiaChi WHERE MaDiaChi=? AND MaNguoiDung=?", id, a.getName());
    if (b.defaultAddress())
      db.exec("UPDATE SoDiaChi SET LaMacDinh=0 WHERE MaNguoiDung=?", a.getName());
    db.exec(
        "UPDATE SoDiaChi SET"
            + " TenNguoiNhan=?,SoDienThoaiNhan=?,SoNhaTenDuong=?,PhuongXa=?,TinhThanhPho=?,LaMacDinh=?"
            + " WHERE MaDiaChi=?",
        b.recipientName(),
        b.phone(),
        b.street().strip(),
        address.ward(),
        address.city(),
        b.defaultAddress(),
        id);
    return Map.of("addressId", id);
  }

  /** Snapshot giao hàng của ERD chỉ có 255 ký tự, cần kiểm tra cả chuỗi đã ghép. */
  private LocationService.AddressText addressText(Address body) {
    var value = locations.validateAddress(body.provinceCode(), body.wardCode(), body.city(), body.ward());
    Problem.require((body.street().strip() + ", " + value.ward() + ", " + value.city()).length() <= 255,
        422, "ADDRESS_TOO_LONG", "Địa chỉ đầy đủ vượt 255 ký tự; rút gọn số nhà/tên đường.");
    return value;
  }

  /** Xóa địa chỉ riêng; FK bảo vệ địa chỉ còn liên kết với đơn. */
  @DeleteMapping("/me/addresses/{id}")
  public Map<String, Object> deleteAddress(Authentication a, @PathVariable String id) {
    Problem.require(
        db.exec("DELETE FROM SoDiaChi WHERE MaDiaChi=? AND MaNguoiDung=?", id, a.getName()) == 1,
        404,
        "NOT_FOUND",
        "Không tìm thấy địa chỉ.");
    return Map.of("deleted", true);
  }
}
