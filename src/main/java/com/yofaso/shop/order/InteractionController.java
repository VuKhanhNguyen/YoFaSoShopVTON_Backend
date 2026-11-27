package com.yofaso.shop.order;

import com.yofaso.shop.ai.MediaService;
import com.yofaso.shop.common.*;
import com.yofaso.shop.notification.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Đánh giá verified buyer (kèm ảnh R2 private kiểm duyệt). */
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
  private final MediaService media;
  private final NotificationService notifications;

  /** Nhận DB, MediaService và NotificationService để quản lý đánh giá và gửi thông báo đa kênh. */
  public InteractionController(Db db, MediaService media, NotificationService notifications) {
    this.db = db;
    this.media = media;
    this.notifications = notifications;
  }

  /** Hiển thị đánh giá đã duyệt cho mọi khách; bài CHO_DUYET chỉ hiện riêng cho chính chủ đăng bài. */
  @GetMapping("/catalog/products/{id}/reviews")
  public List<Map<String, Object>> reviews(
      Authentication auth,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    String currentUser = (auth != null && auth.getName() != null && !auth.getName().isBlank())
        ? auth.getName() : null;
    var rows = db.rows(
        "SELECT d.MaDanhGia AS reviewId, d.MaNguoiDung AS authorId, d.SoSao AS stars, "
            + "d.NoiDungBinhLuan AS comment, d.PhanHoiTuShop AS shopReply, d.TrangThaiDuyet AS status, "
            + "d.NgayDanhGia AS createdAt, d.HinhAnhThucTe AS photoKey, "
            + "u.HoVaTen AS userName, u.AnhDaiDien AS userAvatarKey, "
            + "b.KichCo AS size, b.TenMau AS colorName, b.MaQuanLyHangHoa AS sku "
            + "FROM DanhGiaSanPham d "
            + "JOIN NguoiDung u ON u.MaNguoiDung = d.MaNguoiDung "
            + "LEFT JOIN ChiTietDonHang c ON c.MaChiTietDonHang = d.MaChiTietDonHang "
            + "LEFT JOIN BienTheSanPham b ON b.MaBienThe = c.MaBienThe "
            + "WHERE d.MaSanPham = ? "
            + "  AND (d.TrangThaiDuyet = 'DA_HIEN_THI' "
            + "       OR (? IS NOT NULL AND d.MaNguoiDung = ? AND d.TrangThaiDuyet = 'CHO_DUYET')) "
            + "ORDER BY (d.TrangThaiDuyet = 'CHO_DUYET') DESC, d.NgayDanhGia DESC LIMIT ? OFFSET ?",
        id,
        currentUser,
        currentUser,
        size,
        Db.offset(page, size));
    for (var r : rows) {
      String reviewId = Db.text(r, "reviewId");
      String authorId = Db.text(r, "authorId");
      String photoKey = Db.text(r, "photoKey");
      String avKey = Db.text(r, "userAvatarKey");
      r.put("isOwn", currentUser != null && currentUser.equals(authorId));
      r.put("photoUrl", photoKey.isBlank() ? null : "/api/v1/reviews/" + reviewId + "/image");
      r.put("userAvatar", avKey.isBlank() ? null : "/api/v1/users/" + authorId + "/avatar");
      r.remove("authorId");
      r.remove("photoKey");
      r.remove("userAvatarKey");
    }
    return rows;
  }

  /** Gửi đánh giá JSON (không kèm ảnh). */
  @PostMapping(value = "/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> review(Authentication a, @Valid @RequestBody Review b) throws IOException {
    return createReview(a.getName(), b.orderLineId(), b.stars(), b.comment(), null);
  }

  /** Gửi đánh giá multipart kèm tùy chọn 1 ảnh thực tế lưu vào R2 private bucket. */
  @PostMapping(value = "/reviews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> reviewMultipart(
      Authentication a,
      @RequestParam("orderLineId") String orderLineId,
      @RequestParam("stars") int stars,
      @RequestParam("comment") String comment,
      @RequestParam(value = "file", required = false) MultipartFile file)
      throws IOException {
    return createReview(a.getName(), orderLineId, stars, comment, file);
  }

  private Map<String, Object> createReview(
      String userId, String orderLineId, int stars, String comment, MultipartFile file)
      throws IOException {
    String cleanLineId = orderLineId == null ? "" : orderLineId.trim();
    String cleanComment = comment == null ? "" : comment.trim();
    Problem.require(!cleanLineId.isBlank() && cleanLineId.length() <= 20,
        422, "ORDER_LINE_INVALID", "Mã dòng đơn hàng không hợp lệ.");
    Problem.require(stars >= 1 && stars <= 5,
        422, "STARS_INVALID", "Số sao đánh giá phải từ 1 đến 5.");
    Problem.require(!cleanComment.isBlank() && cleanComment.length() <= 3000,
        422, "COMMENT_INVALID", "Vui lòng nhập nhận xét (tối đa 3000 ký tự).");

    var line =
        db.one(
            "SELECT b.MaSanPham, p.TenSanPham FROM ChiTietDonHang c "
                + "JOIN DonHang d ON d.MaDonHang=c.MaDonHang "
                + "JOIN BienTheSanPham b ON b.MaBienThe=c.MaBienThe "
                + "JOIN SanPham p ON p.MaSanPham=b.MaSanPham "
                + "WHERE c.MaChiTietDonHang=? AND d.MaNguoiDung=? AND d.TrangThaiDonHang='HOAN_TAT'",
            cleanLineId,
            userId);
    long already = db.count("SELECT COUNT(*) FROM DanhGiaSanPham WHERE MaChiTietDonHang=?", cleanLineId);
    Problem.require(already == 0, 409, "REVIEW_ALREADY_EXISTS", "Bạn đã đánh giá sản phẩm này trong đơn hàng rồi.");

    String id = Db.id("DG");
    String productId = Db.text(line, "MaSanPham");
    String productName = Db.text(line, "TenSanPham");
    String photoKey = null;
    if (file != null && !file.isEmpty()) {
      photoKey = media.saveReviewImage(productId, id, file.getBytes());
    }
    try {
      db.exec(
          "INSERT INTO DanhGiaSanPham("
              + "MaDanhGia,MaSanPham,MaNguoiDung,MaChiTietDonHang,SoSao,NoiDungBinhLuan,HinhAnhThucTe,TrangThaiDuyet) "
              + "VALUES(?,?,?,?,?,?,?,'CHO_DUYET')",
          id,
          productId,
          userId,
          cleanLineId,
          stars,
          cleanComment,
          photoKey);
    } catch (RuntimeException e) {
      if (photoKey != null) media.deleteReviewImage(photoKey);
      throw e;
    }

    notifications.notifyAdmins(
        "Đánh giá mới chờ kiểm duyệt (" + stars + "★)",
        "Khách hàng vừa gửi đánh giá cho \"" + productName + "\" (" + id + "). Vui lòng kiểm duyệt để hiển thị.",
        "HE_THONG",
        "/admin/dashboard?tab=operations&view=reviews");

    var resp = new LinkedHashMap<String, Object>();
    resp.put("reviewId", id);
    resp.put("status", "CHO_DUYET");
    resp.put("photoUrl", photoKey == null ? null : "/api/v1/reviews/" + id + "/image");
    return resp;
  }

  /** Endpoint gác cổng duy nhất cho ảnh review: DA_HIEN_THI công khai; CHO_DUYET/DA_AN chỉ chính chủ & Admin. */
  @GetMapping("/reviews/{reviewId}/image")
  public ResponseEntity<byte[]> reviewImage(Authentication auth, @PathVariable String reviewId) {
    var row = db.one(
        "SELECT MaNguoiDung, HinhAnhThucTe, TrangThaiDuyet FROM DanhGiaSanPham WHERE MaDanhGia=?",
        reviewId);
    String photoKey = Db.text(row, "HinhAnhThucTe");
    Problem.require(!photoKey.isBlank(), 404, "NOT_FOUND", "Đánh giá không có ảnh đính kèm.");
    String status = Db.text(row, "TrangThaiDuyet");
    String owner = Db.text(row, "MaNguoiDung");

    boolean isPublic = "DA_HIEN_THI".equals(status);
    if (!isPublic) {
      boolean isAdmin = auth != null
          && auth.getAuthorities().stream().anyMatch(g -> "ROLE_SHOP_ADMIN".equals(g.getAuthority()));
      boolean isOwner = auth != null && owner.equals(auth.getName());
      Problem.require(isAdmin || isOwner, 404, "NOT_FOUND", "Không tìm thấy ảnh đánh giá.");
    }

    byte[] bytes = media.readReviewImage(photoKey);
    CacheControl cc = isPublic
        ? CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic()
        : CacheControl.noStore();
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .contentLength(bytes.length)
        .cacheControl(cc)
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes);
  }

  /** Chủ shop đọc danh sách đánh giá (ưu tiên CHO_DUYET lên đầu) kèm URL proxy ảnh. */
  @GetMapping("/admin/reviews")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> pending(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    var rows = db.rows(
        "SELECT d.MaDanhGia AS reviewId, d.MaSanPham AS productId, s.TenSanPham AS productName, "
            + "COALESCE(u.HoVaTen, 'Khách hàng') AS customerName, d.MaChiTietDonHang AS lineId, "
            + "ct.MaDonHang AS orderId, "
            + "d.SoSao AS stars, d.NoiDungBinhLuan AS comment, d.PhanHoiTuShop AS reply, "
            + "d.TrangThaiDuyet AS status, d.HinhAnhThucTe AS photoKey, d.NgayDanhGia AS createdAt, "
            + "CASE WHEN d.MaChiTietDonHang IS NOT NULL THEN TRUE ELSE FALSE END AS verified "
            + "FROM DanhGiaSanPham d "
            + "LEFT JOIN SanPham s ON s.MaSanPham = d.MaSanPham "
            + "LEFT JOIN NguoiDung u ON u.MaNguoiDung = d.MaNguoiDung "
            + "LEFT JOIN ChiTietDonHang ct ON ct.MaChiTietDonHang = d.MaChiTietDonHang "
            + "ORDER BY CASE WHEN d.TrangThaiDuyet='CHO_DUYET' THEN 0 ELSE 1 END, d.NgayDanhGia DESC LIMIT ? OFFSET ?",
        size,
        Db.offset(page, size));
    for (var r : rows) {
      String reviewId = Db.text(r, "reviewId");
      String photoKey = Db.text(r, "photoKey");
      r.put("photoUrl", photoKey.isBlank() ? null : "/api/v1/reviews/" + reviewId + "/image");
      r.remove("photoKey");
    }
    return rows;
  }

  /** Duyệt/ẩn và phản hồi từ shop; tự động gửi thông báo về Mobile cho khách hàng. */
  @PatchMapping("/admin/reviews/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> moderate(
      @PathVariable String id, @Valid @RequestBody Moderation b, Authentication a) {
    var existing = db.one(
        "SELECT d.MaNguoiDung, d.MaSanPham, d.TrangThaiDuyet, p.TenSanPham "
            + "FROM DanhGiaSanPham d LEFT JOIN SanPham p ON p.MaSanPham = d.MaSanPham "
            + "WHERE d.MaDanhGia=?",
        id);
    String replyText = (b.reply() == null || b.reply().isBlank()) ? null : b.reply().trim();
    db.exec(
        "UPDATE DanhGiaSanPham SET TrangThaiDuyet=?,PhanHoiTuShop=? WHERE MaDanhGia=?",
        b.status(),
        replyText,
        id);
    db.audit(a.getName(), "REVIEW_MODERATE", id + ":" + b.status());

    String owner = Db.text(existing, "MaNguoiDung");
    String productId = Db.text(existing, "MaSanPham");
    String productName = Db.text(existing, "TenSanPham");
    if (!owner.isBlank() && ("DA_HIEN_THI".equals(b.status()) || replyText != null)) {
      String title = replyText != null
          ? "YoFaSo Shop đã phản hồi đánh giá của bạn"
          : "Đánh giá của bạn đã được duyệt hiển thị";
      String body = replyText != null
          ? "Shop vừa phản hồi đánh giá cho \"" + productName + "\": " + replyText
          : "Cảm ơn bạn đã đánh giá \"" + productName + "\". Bài viết hiện đã hiển thị công khai.";
      notifications.notifyUser(owner, title, body, "HE_THONG", "/product/" + productId);
    }
    return Map.of("updated", true, "status", b.status());
  }
}
