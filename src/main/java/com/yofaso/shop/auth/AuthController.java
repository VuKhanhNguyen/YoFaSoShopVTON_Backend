package com.yofaso.shop.auth;

import com.yofaso.shop.ai.MediaService;
import com.yofaso.shop.ai.R2Storage;
import com.yofaso.shop.common.*;
import com.yofaso.shop.location.LocationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Đăng nhập, hồ sơ và sổ địa chỉ của chính người đang đăng nhập. */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
  public record Register(
      @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{3,15}") String username,
      @NotBlank @Size(min = 10, max = 72) String password,
      @Size(max = 100) String fullName,
      @Pattern(regexp = "[0-9+]{9,15}") @NotNull String phone,
      @NotBlank @Email @Size(max = 100) String email,
      LocalDate dateOfBirth) {
    public Register(String username, String password, String fullName, String phone, String email) {
      this(username, password, fullName, phone, email, null);
    }
  }

  public record Login(
      @NotBlank @Size(min = 3, max = 100) String username, @NotBlank @Size(min = 10, max = 72) String password) {}

  public record Token(@NotBlank @Size(max = 100) String refreshToken) {}

  public record Profile(
      @NotBlank @Size(max = 100) String fullName,
      @Email @Size(max = 100) String email,
      @Pattern(regexp = "[0-9+]{9,15}") String phone,
      @Size(max=72) String currentPassword,
      LocalDate dateOfBirth) {}

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
  private final MediaService media;
  private final R2Storage r2;

  /** Nhận service tài khoản và kho dữ liệu địa chỉ. */
  public AuthController(AuthService auth, Db db, LocationService locations, MediaService media, R2Storage r2) {
    this.auth = auth;
    this.db = db;
    this.locations = locations;
    this.media = media;
    this.r2 = r2;
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
        "UPDATE NguoiDung SET HoVaTen=?,Email=COALESCE(?,Email),SoDienThoai=COALESCE(?,SoDienThoai),NgaySinh=COALESCE(?,NgaySinh) WHERE"
            + " MaNguoiDung=?",
        b.fullName(),
        email,
        b.phone(),
        b.dateOfBirth(),
        a.getName());
    return auth.me(a.getName());
  }

  /** Xem ảnh đại diện của người dùng theo ID (public cache 7 ngày). */
  @GetMapping("/users/{id}/avatar")
  public ResponseEntity<byte[]> userAvatar(@PathVariable String id) {
    var user = db.one("SELECT AnhDaiDien FROM NguoiDung WHERE MaNguoiDung=?", id);
    String key = Db.text(user, "AnhDaiDien");
    Problem.require(!key.isEmpty(), 404, "NOT_FOUND", "Người dùng chưa có ảnh đại diện.");
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(org.springframework.http.CacheControl.maxAge(7, java.util.concurrent.TimeUnit.DAYS).cachePublic())
        .header("X-Content-Type-Options", "nosniff")
        .body(media.catalogBytes(key));
  }

  /** Cập nhật ảnh đại diện của chính mình, upload lên Cloudflare R2 và xóa ảnh cũ trên R2. */
  @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String, Object> updateAvatar(Authentication a, @RequestParam("file") MultipartFile file)
      throws IOException {
    Problem.require(file != null && !file.isEmpty(), 400, "FILE_EMPTY", "File ảnh không được để trống.");
    byte[] rawBytes = file.getBytes();
    Problem.require(rawBytes.length <= 10 * 1024 * 1024, 413, "FILE_TOO_LARGE", "Kích thước ảnh tối đa 10 MB.");

    byte[] pngBytes;
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(rawBytes))) {
      var readers = ImageIO.getImageReaders(stream);
      Problem.require(readers.hasNext(), 415, "IMAGE_INVALID", "Chỉ nhận ảnh PNG hoặc JPEG thực.");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        Problem.require(
            format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"),
            415,
            "IMAGE_INVALID",
            "Chỉ hỗ trợ PNG/JPEG.");
        reader.setInput(stream, true, true);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        Problem.require(
            w >= 64 && h >= 64 && (long) w * h <= 25000000,
            422,
            "IMAGE_DIMENSIONS_INVALID",
            "Cạnh ảnh tối thiểu 64px, tối đa 25 triệu pixel.");
        var image = com.yofaso.shop.ai.ImageOrientation.apply(reader.read(0), com.yofaso.shop.ai.ImageOrientation.read(rawBytes));
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        pngBytes = out.toByteArray();
      } finally {
        reader.dispose();
      }
    }

    String userId = a.getName();
    String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String newKey = "avatars/" + userId + "/avatar-" + uuid + ".png";

    // Upload lên Cloudflare R2 và lưu cache local
    r2.upload(newKey, pngBytes);
    media.saveToCache(newKey, pngBytes);

    // Lấy avatar cũ để xóa sau khi lưu DB thành công
    var oldUser = db.one("SELECT AnhDaiDien FROM NguoiDung WHERE MaNguoiDung=?", userId);
    String oldKey = Db.text(oldUser, "AnhDaiDien");

    db.exec("UPDATE NguoiDung SET AnhDaiDien=? WHERE MaNguoiDung=?", newKey, userId);

    if (!oldKey.isEmpty() && !oldKey.equals(newKey)) {
      r2.delete(oldKey);
      media.evictCache(oldKey);
    }

    return auth.me(userId);
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
