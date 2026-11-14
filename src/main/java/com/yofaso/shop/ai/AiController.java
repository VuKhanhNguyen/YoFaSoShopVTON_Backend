package com.yofaso.shop.ai;

import com.yofaso.shop.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** Cổng AI cho FE: ownership, catalog và lưu ảnh do Backend quản lý. */
@RestController
@RequestMapping("/api/v1")
public class AiController {
  public record TryOn(
      @NotBlank @Size(max = 20) String variantId,
      @NotBlank @Size(max = 64) String personAssetId,
      @Min(0) @Max(2147483647) int seed,
      @Min(10) @Max(50) int steps,
      @Min(1) @Max(4) int samples,
      @AssertTrue(message = "Cần đồng ý xử lý ảnh để thử đồ.") boolean consent) {}

  public record Person(
      @NotBlank @Size(max = 64) String assetId,
      @Pattern(regexp = "upper_body|lower_body|dress") String vtonCategory) {}

  public record Route(
      @NotNull @Pattern(regexp = "upper_body|lower_body|dress") String vtonCategory,
      @NotNull @Pattern(regexp = "full_body|three_quarter|half_body|invalid") String personFrame,
      @Size(max = 64) String garmentSubtype,
      @NotNull @Pattern(regexp = "SHORT|MIDI|LONG|UNKNOWN") String garmentLength) {}

  public record Analysis(
      @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 64) String> productAssetIds,
      @NotNull @Size(max = 8) List<@NotBlank @Size(max = 64) String> referenceAssetIds) {}

  public record SegmentRequest(
      @NotBlank @Size(max = 64) String garmentAssetId,
      @Size(max = 64) String referenceAssetId,
      @Size(max = 64) String vtonCategory,
      @Size(max = 64) String subtype) {}

  public record Binding(
      @NotBlank @Size(max = 20) String variantId, @NotBlank @Size(max = 64) String sourceAssetId) {}

  public record Approval(@NotEmpty @Size(max = 50) List<@Valid Binding> bindings) {}

  private final Db db;
  private final AiClient client;
  private final AiService service;
  private final MediaService media;
  private final JsonMapper json;

  /** Nhận orchestration, storage và client nội bộ; FE không có key AI. */
  public AiController(
      Db db, AiClient client, AiService service, MediaService media, JsonMapper json) {
    this.db = db;
    this.client = client;
    this.service = service;
    this.media = media;
    this.json = json;
  }

  /** Upload ảnh cá nhân; ảnh sản phẩm/ảnh mẫu chỉ dành admin. */
  @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> upload(
      Authentication a,
      @RequestParam MultipartFile file,
      @RequestParam String role,
      @RequestParam boolean consent)
      throws java.io.IOException {
    Problem.require(consent, 422, "CONSENT_REQUIRED", "Cần đồng ý xử lý ảnh trước khi tải lên.");
    Problem.require(
        Set.of("person", "garment", "reference").contains(role),
        422,
        "ASSET_ROLE_INVALID",
        "role: person, garment hoặc reference.");
    Problem.require(
        role.equals("person")
            || a.getAuthorities().stream()
                .anyMatch(x -> x.getAuthority().equals("ROLE_SHOP_ADMIN")),
        403,
        "FORBIDDEN",
        "Chỉ chủ shop tải ảnh catalog.");
    var saved = new LinkedHashMap<String, Object>(media.save(a.getName(), role, file.getBytes(), false));
    if ("garment".equals(role)) {
      try {
        var colorAnalysis = media.detectGarmentColor(file.getBytes());
        if (colorAnalysis != null && !colorAnalysis.isEmpty()) {
          var dominant = (Map<?, ?>) colorAnalysis.get("dominantColor");
          if (dominant != null) {
            saved.put("colorName", dominant.get("name"));
            saved.put("dominantHex", dominant.get("hex"));
          }
          if (colorAnalysis.containsKey("palette")) {
            saved.put("palette", colorAnalysis.get("palette"));
          }
          if (colorAnalysis.get("vtonCategory") != null) {
            saved.put("vtonCategory", colorAnalysis.get("vtonCategory"));
          }
          if (colorAnalysis.get("subtype") != null) {
            saved.put("subtype", colorAnalysis.get("subtype"));
          }
        }
      } catch (Exception ignored) {
        // Giữ assetId cho client, fallback an toàn nếu AI bận
      }
    }
    return saved;
  }

  /** Nhận diện lại màu sắc và phân loại bằng AI cho ảnh catalog đã lưu. */
  @PostMapping("/assets/{id}/detect-color")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> detectColor(Authentication a, @PathVariable String id) {
    media.owned(id, a.getName());
    return media.detectGarmentColor(media.bytes(id));
  }

  /** Tải ảnh cá nhân/kết quả riêng, không trả filesystem path. */
  @GetMapping("/assets/{id}/content")
  public ResponseEntity<byte[]> content(Authentication a, @PathVariable String id) {
    media.owned(id, a.getName());
    return png(media.bytes(id));
  }

  /** Xóa ảnh riêng chưa dùng trong lịch sử/catalog/job, tránh phá ảnh của phiên đang chạy. */
  @DeleteMapping("/assets/{id}")
  public Map<String, Object> deleteAsset(Authentication a, @PathVariable String id) {
    media.delete(id, a.getName());
    return Map.of("deleted", true);
  }

  /** Chỉ các asset catalog của sản phẩm đang bán mới được công khai. */
  @GetMapping("/catalog/assets/{id}/content")
  public ResponseEntity<byte[]> catalogContent(@PathVariable String id) {
    Problem.require(
        db.count(
                "SELECT COUNT(*) FROM HinhAnhSanPham h JOIN SanPham p ON p.MaSanPham=h.MaSanPham"
                    + " WHERE p.TrangThaiSanPham='DANG_KINH_DOANH' AND (h.DuongDanAnhGoc=? OR"
                    + " h.DuongDanAnhBocNen=?)",
                id,
                id)
            > 0,
        404,
        "NOT_FOUND",
        "Không có ảnh công khai.");
    return png(media.bytes(id));
  }

  /** Ảnh seed SQL được định danh bằng MaHinhAnh, không đưa object key tùy ý vào URL proxy. */
  @GetMapping("/catalog/images/{id}/content")
  public ResponseEntity<byte[]> catalogImage(@PathVariable String id,
      @RequestParam(defaultValue="original") String kind) {
    Problem.require(Set.of("original", "cutout").contains(kind), 400, "IMAGE_KIND_INVALID", "kind cần original hoặc cutout.");
    var row = db.one("SELECT h.DuongDanAnhGoc,h.DuongDanAnhBocNen FROM HinhAnhSanPham h JOIN SanPham p ON p.MaSanPham=h.MaSanPham"
        + " WHERE h.MaHinhAnh=? AND p.TrangThaiSanPham='DANG_KINH_DOANH'", id);
    String key = Db.text(row, kind.equals("cutout") ? "DuongDanAnhBocNen" : "DuongDanAnhGoc");
    Problem.require(!key.isEmpty(), 404, "NOT_FOUND", "Ảnh này chưa có dữ liệu.");
    byte[] bytes = media.catalogBytes(key);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .contentLength(bytes.length)
        .cacheControl(org.springframework.http.CacheControl.maxAge(7, java.util.concurrent.TimeUnit.DAYS).cachePublic())
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes);
  }

  /** Ảnh sản phẩm ở mọi trạng thái (kể cả bản nháp) dành cho Shop Admin xem & kiểm duyệt. */
  @GetMapping("/admin/products/{productId}/images/{imageId}/content")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public ResponseEntity<byte[]> adminProductImage(
      @PathVariable String productId,
      @PathVariable String imageId,
      @RequestParam(defaultValue = "original") String kind) {
    Problem.require(Set.of("original", "cutout").contains(kind), 400, "IMAGE_KIND_INVALID", "kind cần original hoặc cutout.");
    var row = db.one(
        "SELECT DuongDanAnhGoc,DuongDanAnhBocNen FROM HinhAnhSanPham WHERE MaHinhAnh=? AND MaSanPham=?",
        imageId,
        productId);
    String key = Db.text(row, kind.equals("cutout") ? "DuongDanAnhBocNen" : "DuongDanAnhGoc");
    Problem.require(!key.isEmpty(), 404, "NOT_FOUND", "Ảnh này chưa có dữ liệu.");
    return png(media.catalogBytes(key));
  }

  /** Ảnh Agnostic Mask của sản phẩm dành cho Shop Admin kiểm tra qua AI Inspector. */
  @GetMapping("/admin/products/{productId}/mask/content")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public ResponseEntity<byte[]> adminProductMask(@PathVariable String productId) {
    var row = db.one("SELECT DuongDanMatNaVungThayDo FROM SanPham WHERE MaSanPham=?", productId);
    String key = Db.text(row, "DuongDanMatNaVungThayDo");
    Problem.require(!key.isEmpty(), 404, "NOT_FOUND", "Sản phẩm chưa có ảnh Agnostic Mask.");
    return png(media.catalogBytes(key));
  }

  /** Kiểm tra ảnh người thật qua AI; mode mock của AI vẫn được giữ nguyên cờ simulated. */
  @PostMapping("/ai/persons/validate")
  public Map<String, Object> validatePerson(Authentication a, @Valid @RequestBody Person b) {
    var row = media.owned(b.assetId(), a.getName());
    Problem.require(
        "person".equals(row.get("RoleName")), 422, "ASSET_ROLE_INVALID", "Cần role person.");
    var body = new HashMap<String, Object>();
    body.put("assetId", media.remote(b.assetId()));
    if (b.vtonCategory() != null) body.put("vtonCategory", b.vtonCategory());
    return client.post("/internal/v1/persons/validate", body);
  }

  /** Preview quy tắc chỉ để hướng dẫn UI, không thay kiểm tra ảnh thật. */
  @PostMapping("/ai/routing/preview")
  public Map<String, Object> routing(@Valid @RequestBody Route b) {
    return client.post("/internal/v1/routing/preview", b);
  }

  /** Tạo phiên bền trong MySQL, tác vụ nền sẽ submit sang FastAPI. */
  @PostMapping("/try-on/sessions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> create(
      Authentication a, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TryOn b) {
    return service.create(a.getName(), key, b);
  }

  /** Poll chỉ trả phiên do người dùng hiện tại tạo. */
  @GetMapping("/try-on/sessions/{id}")
  public Map<String, Object> session(Authentication a, @PathVariable String id) {
    var op = service.operation(id, a.getName());
    Problem.require("TRY_ON".equals(op.get("kind")), 404, "NOT_FOUND", "Không có phiên thử đồ.");
    return op;
  }

  /** Lịch sử cá nhân không chứa input ảnh của người khác. */
  @GetMapping("/try-on/sessions")
  public List<Map<String, Object>> history(
      Authentication a,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return db.rows(
        "SELECT MaPhienThuDo AS sessionId,MaBienThe AS variantId,TrangThaiPhien AS"
            + " status,DuongDanAnhKetQuaVTON AS resultAssetId,ThongBaoLoi AS error,ThoiGianKhoiTao AS createdAt FROM PhienThuDoAo WHERE MaNguoiDung=? ORDER"
            + " BY ThoiGianKhoiTao DESC,MaPhienThuDo LIMIT ? OFFSET ?",
        a.getName(),
        size,
        Db.offset(page, size));
  }

  /** Chỉ hủy job AI còn đang xếp hàng; đang render có thể trả 409. */
  @PostMapping("/try-on/sessions/{id}/cancel")
  public Map<String, Object> cancel(Authentication a, @PathVariable String id) {
    return service.control(id, a.getName(), "cancel");
  }

  /** Thử đối soát lại hoặc gọi retry theo policy lỗi tạm của AI. */
  @PostMapping("/try-on/sessions/{id}/retry")
  public Map<String, Object> retry(Authentication a, @PathVariable String id) {
    return service.control(id, a.getName(), "retry");
  }

  /** Submit phân tích catalog, không công bố sản phẩm tự động. */
  @PostMapping("/admin/products/{id}/analysis")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> analysis(
      Authentication a,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Analysis b) {
    Problem.require(
        new HashSet<>(b.productAssetIds()).size() == b.productAssetIds().size()
            && new HashSet<>(b.referenceAssetIds()).size() == b.referenceAssetIds().size(),
        422,
        "DUPLICATE_ASSET",
        "Danh sách ảnh có ID lặp.");
    return service.analyze(a.getName(), key, id, b);
  }

  /** Bóc tách nền và tạo Agnostic Mask realtime từ GPU cho một ảnh trang phục. */
  @PostMapping("/admin/ai/segment-garment")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> segmentGarment(
      Authentication a, @Valid @RequestBody SegmentRequest req) {
    return media.segmentGarment(
        a.getName(),
        req.garmentAssetId(),
        req.referenceAssetId(),
        req.vtonCategory(),
        req.subtype());
  }

  /** Kết quả từng ảnh giữ quan hệ N ảnh nguồn/N cutout/N mask. */
  @GetMapping("/admin/analyses/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> analysisStatus(Authentication a, @PathVariable String id) {
    return service.operation(id, a.getName());
  }

  /** Duyệt mapping ảnh→SKU từ kết quả thật; không nhận path hay metadata tùy ý từ client. */
  @PostMapping("/admin/analyses/{id}/approve")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @SuppressWarnings("unchecked")
  public Map<String, Object> approve(
      Authentication a, @PathVariable String id, @Valid @RequestBody Approval b) {
    var op = service.operation(id, a.getName());
    Problem.require(
        "PRODUCT_ANALYSIS".equals(op.get("kind")) && "COMPLETED".equals(op.get("status")),
        409,
        "ANALYSIS_NOT_READY",
        "Chưa có kết quả phân tích hoàn tất.");
    Problem.require(
        !Boolean.TRUE.equals(op.get("simulated"))
            && !"1".equals(Objects.toString(op.get("simulated"))),
        409,
        "MOCK_RESULT_REJECTED",
        "Không duyệt kết quả giả lập để bán hàng thật.");
    var result = (Map<String, Object>) op.get("result");
    String product = op.get("resourceId").toString();
    db.one("SELECT MaSanPham FROM SanPham WHERE MaSanPham=? FOR UPDATE", product);
    Set<String> variants = new HashSet<>();
    for (var bind : b.bindings()) {
      Problem.require(
          variants.add(bind.variantId()),
          422,
          "DUPLICATE_VARIANT",
          "Mỗi SKU chỉ bind một ảnh trong request.");
      var sku = db.one(
          "SELECT MaBienThe,MaMauSac FROM BienTheSanPham WHERE MaBienThe=? AND MaSanPham=?",
          bind.variantId(),
          product);
      var meta =
          ((List<Map<String, Object>>) result.get("variants"))
              .stream()
                  .filter(v -> bind.sourceAssetId().equals(v.get("sourceAssetId")))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new Problem(
                              422, "ASSET_MAPPING_INVALID", "Ảnh không có trong phiên phân tích."));
      String original=Objects.toString(meta.get("originalKey"),"");
      String cutout=Objects.toString(meta.get("cutoutKey"),"");
      Problem.require(!original.isEmpty() && !cutout.isEmpty(),409,"R2_SYNC_PENDING","Ảnh chưa đồng bộ R2.");
      var existing = db.rows("SELECT MaHinhAnh FROM HinhAnhSanPham WHERE MaSanPham=? AND DuongDanAnhGoc=?",product,original);
      // Một ảnh cho một màu; các size cùng màu dùng chung ảnh. Không xóa ảnh màu khác.
      Problem.require(existing.isEmpty() || db.count("SELECT COUNT(*) FROM HinhAnhSanPham WHERE MaSanPham=? AND DuongDanAnhGoc=? AND MaMauSac=?",product,original,sku.get("MaMauSac"))==existing.size(),422,"IMAGE_COLOR_CONFLICT","Ảnh này đã liên kết màu khác.");
      int position=((List<Map<String,Object>>)result.get("variants")).indexOf(meta);
      long refCoverCount = db.count("SELECT COUNT(*) FROM HinhAnhSanPham WHERE MaSanPham=? AND LaAnhDaiDien=1", product);
      boolean isCover = refCoverCount == 0 && position == 0;
      long maxPosition = db.count("SELECT COALESCE(MAX(ThuTuHienThi), 0) FROM HinhAnhSanPham WHERE MaSanPham=?", product);
      int finalPos = (int) (maxPosition + position + 1);
      if (existing.isEmpty()) db.exec("INSERT INTO HinhAnhSanPham(MaHinhAnh,MaSanPham,DuongDanAnhGoc,DuongDanAnhBocNen,MaMauSac,LaAnhDaiDien,ThuTuHienThi) VALUES(?,?,?,?,?,?,?)",
          Db.id("HA"),product,original,cutout,sku.get("MaMauSac"),isCover,finalPos);
    }
    var analyzed=(List<Map<String,Object>>)result.get("variants");
    String representative=Objects.toString(analyzed.getFirst().get("maskKey"),"");
    Problem.require(!representative.isEmpty(),409,"R2_SYNC_PENDING","Thiếu mask img01 trên R2.");
    Problem.require(Set.of("upper_body","lower_body","dress").contains(result.get("vtonCategory")),502,"AI_RESPONSE_INVALID","Category không hợp lệ.");
    String subtype=Objects.toString(analyzed.getFirst().get("category"),"");
    Problem.require(!subtype.isBlank() && subtype.length()<=50,502,"AI_RESPONSE_INVALID","Thiếu subtype hợp lệ.");
    var palette=analyzed.stream().map(v -> RuntimeState.row("sourceAssetId",v.get("sourceAssetId"),"colors",v.get("colors"))).toList();
    db.exec("UPDATE SanPham SET PhanLoaiVTON=?,TieuLoaiTrangPhuc=?,BangMauTrichXuat=?,DuongDanMatNaVungThayDo=?,TrangThaiSanPham='BAN_NHAP' WHERE MaSanPham=?",
        result.get("vtonCategory"),subtype,json.writeValueAsString(palette),representative,product);
    db.audit(a.getName(), "ANALYSIS_APPROVE", id);
    return Map.of("approved", true, "published", false);
  }

  /** Response ảnh có MIME cố định và nosniff; không nhúng secret trong URL. */
  private static ResponseEntity<byte[]> png(byte[] bytes) {
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .contentLength(bytes.length)
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes);
  }
}
