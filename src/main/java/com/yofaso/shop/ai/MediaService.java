package com.yofaso.shop.ai;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Idempotency;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.common.RuntimeState;

/** Storage local private: ảnh PNG chuẩn hóa, metadata gốc được bỏ, không dùng tên upload. */
@Service
public class MediaService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MediaService.class);
  private final Path root;
  private final Path cacheDir;
  private final Path chatDir;
  private final Path reviewDir;
  private final Db db;
  private final AiClient ai;
  private final R2Storage r2;
  private final String catalogBase;
  private final java.net.http.HttpClient catalogHttp;

  /** Chỉ thư mục cấu hình server được dùng để ghi file. */
  public MediaService(@Value("${app.storage-root}") String root, Db db, AiClient ai, R2Storage r2,
      @Value("${app.catalog.public-base-url:}") String catalogBase)
      throws IOException {
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(this.root);
    this.cacheDir = this.root.resolve("catalog-cache");
    Files.createDirectories(this.cacheDir);
    this.chatDir = this.root.resolve("chat-cache");
    Files.createDirectories(this.chatDir);
    this.reviewDir = this.root.resolve("review-cache");
    Files.createDirectories(this.reviewDir);
    this.db = db;
    this.ai = ai;
    this.r2 = r2;
    this.catalogBase = catalogBase.replaceAll("/+$", "");
    if (!this.catalogBase.isEmpty()) {
      var uri = java.net.URI.create(this.catalogBase);
      Problem.require("https".equals(uri.getScheme()) && uri.getHost() != null
          && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
          500, "CATALOG_CONFIG_INVALID", "R2_PUBLIC_BASE_URL cần HTTPS và không có query/credential.");
    }
    catalogHttp = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
  }

  /**
   * Kiểm tra PNG/JPEG, số pixel và decode trước khi lưu; không suy ra người/garment bằng extension.
   */
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> save(String owner, String role, byte[] bytes, boolean simulated) {
    return save(owner, role, bytes, simulated, "person".equals(role));
  }

  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> save(String owner, String role, byte[] bytes, boolean simulated, boolean enforceQuota) {
    return saveInternal(owner, role, bytes, simulated, enforceQuota);
  }

  private Map<String, Object> saveInternal(String owner, String role, byte[] bytes, boolean simulated, boolean enforceQuota) {
    boolean source = Set.of("person", "garment", "reference").contains(role);
    if (source) db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", owner);
    Problem.require(
        bytes.length > 0 && bytes.length <= 60 * 1024 * 1024,
        413,
        "UPLOAD_TOO_LARGE",
        "Ảnh rỗng hoặc vượt 60 MB.");
    if ("person".equals(role) && enforceQuota)
      Problem.require(
          db.runtime().find("media", r -> owner.equals(r.get("OwnerId"))
              && "person".equals(r.get("RoleName"))
              && r.get("ShopModelId") == null
              && r.get("R2Key") == null).size()
              < 100,
          429,
          "ASSET_QUOTA",
          "Đã đạt giới hạn 100 ảnh người dùng cho tài khoản.");
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      Problem.require(readers.hasNext(), 415, "IMAGE_INVALID", "Chỉ nhận ảnh PNG hoặc JPEG thực.");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        Problem.require(
            format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"),
            415,
            "IMAGE_INVALID",
            "Chỉ hỗ trợ PNG/JPEG ở Backend.");
        reader.setInput(stream, true, true);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        Problem.require(
            w >= (source ? 128 : 1) && h >= (source ? 128 : 1) && (long) w * h <= 60000000,
            422,
            "IMAGE_DIMENSIONS_INVALID",
            "Cạnh ảnh tối thiểu 128px, tối đa 60 triệu pixel.");
        var image = ImageOrientation.apply(reader.read(0), ImageOrientation.read(bytes));
        w = image.getWidth();
        h = image.getHeight();
        String id = UUID.randomUUID().toString().replace("-", "");
        Path path = root.resolve(id + ".png");
        ImageIO.write(image, "png", path.toFile());
        try {
          db.runtime().put("media", id, RuntimeState.row("AssetId",id,"OwnerId",owner,"RoleName",role,"LocalFile",id + ".png","Simulated",simulated));
        } catch (RuntimeException e) {
          Files.deleteIfExists(path);
          throw e;
        }
        return Map.of("assetId", id, "role", role, "width", w, "height", h, "simulated", simulated);
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new Problem(415, "IMAGE_INVALID", "Ảnh lỗi hoặc storage không ghi được.");
    }
  }

  /** Chặn truy cập ảnh cá nhân của người khác, kể cả khi biết UUID. */
  public Map<String, Object> owned(String id, String owner) {
    return ownedRecord(id, owner);
  }

  synchronized String shopPerson(String owner, String model) {
    Problem.require(Set.of("female", "male", "female_upper", "male_upper").contains(model), 422, "SHOP_MODEL_INVALID", "Mẫu shop không hợp lệ.");
    var existing = db.runtime().find("media", row -> owner.equals(row.get("OwnerId"))
        && "person".equals(row.get("RoleName")) && model.equals(row.get("ShopModelId")));
    try (var input = new org.springframework.core.io.ClassPathResource("vton-models/" + model + ".png").getInputStream()) {
      byte[] bytes = input.readAllBytes();
      for (var row : existing) {
        String existingId = Db.text(row, "AssetId");
        Path existingFile = root.resolve(existingId + ".png");
        if (!Files.exists(existingFile)) continue;
        // ImageIO nén lại PNG nên kích thước file lưu khác bytes.length ảnh gốc; so theo SourceBytes để tránh tạo bản trùng mỗi lượt thử đồ.
        Object recorded = row.get("SourceBytes");
        if ((recorded instanceof Number n && n.longValue() == bytes.length)
            || (recorded == null && Files.size(existingFile) > 0)) {
          if (recorded == null) db.runtime().patch("media", existingId, "SourceBytes", bytes.length);
          return existingId;
        }
      }
      String id = Db.text(saveInternal(owner, "person", bytes, false, false), "assetId");
      db.runtime().patch("media", id, "ShopModelId", model, "SourceBytes", bytes.length);
      return id;
    } catch (IOException e) {
      throw new Problem(503, "SHOP_MODEL_UNAVAILABLE", "Ảnh mẫu shop chưa sẵn sàng.");
    }
  }

  public void registerR2Asset(String owner, String key, String role, String localFile) {
    db.runtime().put("media", key, RuntimeState.row("AssetId", key, "OwnerId", owner, "RoleName", role, "LocalFile", localFile, "R2Key", key));
  }

  public boolean isR2Configured() {
    return r2 != null && r2.isConfigured();
  }

  public void uploadR2(String key, byte[] bytes) {
    if (r2 != null) r2.upload(key, bytes);
  }

  String readableAsset(String id, String owner) {
    if (id == null || id.isBlank()) return null;
    var row = db.runtime().findById("media", id);
    if (row != null && owner.equals(row.get("OwnerId"))) return id;
    if (id.startsWith("tryon/")) {
      long count = db.count(
          "SELECT COUNT(*) FROM PhienThuDoAo WHERE (DuongDanAnhNguoiDung=? OR DuongDanAnhKetQuaVTON=?) AND MaNguoiDung=?",
          id, id, owner);
      if (count > 0) return id;
    }
    return null;
  }

  String shopModelId(String id) {
    var row = db.runtime().findById("media", id);
    return row == null ? "" : Db.text(row, "ShopModelId");
  }

  String sourceSessionId(String id) {
    var row = db.runtime().findById("media", id);
    return row == null ? "" : Db.text(row, "SourceSessionId");
  }

  byte[] thumbnail(String id) {
    try {
      byte[] original = bytes(id);
      var source = ImageIO.read(new ByteArrayInputStream(original));
      if (source == null) throw new IOException("Unreadable PNG");
      double scale = Math.min(1, 240.0 / Math.max(source.getWidth(), source.getHeight()));
      if (scale == 1) return original;
      var thumb = new java.awt.image.BufferedImage(Math.max(1, (int)(source.getWidth() * scale)),
          Math.max(1, (int)(source.getHeight() * scale)), java.awt.image.BufferedImage.TYPE_INT_ARGB);
      var graphics = thumb.createGraphics();
      try {
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, thumb.getWidth(), thumb.getHeight(), null);
      } finally {
        graphics.dispose();
      }
      var output = new ByteArrayOutputStream();
      ImageIO.write(thumb, "png", output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new Problem(503, "ASSET_UNAVAILABLE", "Không đọc được ảnh thu nhỏ.");
    }
  }

  private Map<String,Object> ownedRecord(String id, String owner) {
    var row = db.runtime().get("media", id);
    if (row != null && owner.equals(row.get("OwnerId"))) return row;
    if (id != null && id.startsWith("tryon/")) {
      var sessionRows = db.rows(
          "SELECT MaNguoiDung FROM PhienThuDoAo WHERE (DuongDanAnhNguoiDung=? OR DuongDanAnhKetQuaVTON=?) AND MaNguoiDung=?",
          id, id, owner);
      if (!sessionRows.isEmpty()) {
        return RuntimeState.row("AssetId", id, "OwnerId", owner, "RoleName", id.contains("--result") ? "result" : "person");
      }
    }
    Problem.require(false,404,"NOT_FOUND","Không tìm thấy ảnh.");
    return null;
  }

  /** Đọc file từ filename do server tạo, không từ đường dẫn client truyền. */
  public byte[] bytes(String id) {
    var row = db.runtime().findById("media", id);
    Path p = null;
    if (row != null && !row.isEmpty() && !Db.text(row, "LocalFile").isBlank()) {
      p = root.resolve(Db.text(row, "LocalFile")).normalize();
    } else if (Files.isRegularFile(root.resolve(id + ".png"))) {
      p = root.resolve(id + ".png");
    } else if (Files.isRegularFile(root.resolve(id))) {
      p = root.resolve(id);
    }
    if (p != null && Files.isRegularFile(p) && p.startsWith(root)) {
      try {
        return Files.readAllBytes(p);
      } catch (IOException ignored) {}
    }
    if (id != null && (id.startsWith("tryon/") || (row != null && Db.text(row, "R2Key").startsWith("tryon/")))) {
      String key = id.startsWith("tryon/") ? id : Db.text(row, "R2Key");
      if (r2.isConfigured()) {
        try {
          return r2.get(key);
        } catch (Exception ignored) {}
      }
    }
    throw new Problem(503, "ASSET_UNAVAILABLE", "Ảnh không còn trên storage: " + id);
  }

  /** Gọi AI Service phân tích màu sản phẩm; fallback rỗng nếu AI bận. */
  public Map<String, Object> detectGarmentColor(byte[] bytes) {
    try {
      return ai.analyzeGarmentColor(bytes);
    } catch (Exception e) {
      return Map.of();
    }
  }

  /** Bóc tách nền và tạo mask realtime từ YOLOv8s-seg + SAM 2 trên GPU. */
  public Map<String, Object> segmentGarment(
      String owner, String garmentAssetId, String referenceAssetId, String category, String subtype) {
    try {
      Problem.require("garment".equals(owned(garmentAssetId, owner).get("RoleName")),
          422, "ASSET_ROLE_INVALID", "Cần ảnh trang phục.");
      if (referenceAssetId != null && !referenceAssetId.isBlank()) {
        Problem.require("reference".equals(owned(referenceAssetId, owner).get("RoleName")),
            422, "ASSET_ROLE_INVALID", "Cần ảnh người mẫu.");
      }
      String remoteGarment = remote(garmentAssetId);
      String remoteRef = (referenceAssetId != null && !referenceAssetId.isBlank()) ? remote(referenceAssetId) : null;
      var remoteRes = ai.segmentGarmentRealtime(remoteGarment, remoteRef, category, subtype);
      if (remoteRes == null || remoteRes.isEmpty()) return Map.of();

      String remoteCutout = Objects.toString(remoteRes.get("cutoutAssetId"), "");
      String remoteMask = Objects.toString(remoteRes.get("maskAssetId"), "");
      boolean simulated = Boolean.TRUE.equals(remoteRes.get("simulated"));

      String localCutout = remoteCutout.isEmpty() ? null : importResult(owner, "cutout", remoteCutout, simulated);
      String localMask = remoteMask.isEmpty() ? null : importResult(owner, "mask", remoteMask, simulated);

      var res = new LinkedHashMap<String, Object>(remoteRes);
      if (localCutout != null) {
        res.put("cutoutAssetId", localCutout);
        res.put("cutoutUrl", "/assets/" + localCutout + "/content");
      }
      if (localMask != null) {
        res.put("maskAssetId", localMask);
        res.put("maskUrl", "/assets/" + localMask + "/content");
      }
      return res;
    } catch (Exception e) {
      log.error("MediaService segmentGarment error: {}", e.getMessage(), e);
      return Map.of();
    }
  }

  /** Upload ảnh đã lưu sang AI một lần rồi lưu mapping ID bền vững; tự upload lại nếu AI server mới trả 404. */
  public String remote(String id) {
    var row = db.runtime().get("media", id);
    String remote = Db.text(row, "AiAssetId");
    if (!remote.isEmpty()) {
      try {
        ai.getVton("/internal/v1/assets/" + AiClient.id(remote));
        return remote;
      } catch (Problem e) {
        if (e.status != 404) throw e;
        log.info("Cached AiAssetId {} missing on AI server (404); re-uploading local asset {}", remote, id);
      }
    }
    String role = Db.text(row, "RoleName");
    Problem.require(
        Set.of("person", "garment", "reference").contains(role),
        422,
        "ASSET_ROLE_INVALID",
        "Ảnh kết quả không được dùng sai vai trò.");
    remote = AiClient.id(Objects.toString(ai.upload(bytes(id), role).get("assetId"), ""));
    db.runtime().patch("media", id, "AiAssetId", remote);
    return remote;
  }

  /** Cutout nhỏ vẫn giữ nguyên trên storage; chỉ đệm nền trắng khi cần gửi như input AI >=128px. */
  public byte[] garmentInput(String id) {
    byte[] data = catalogBytes(id);
    try {
      var image = ImageIO.read(new ByteArrayInputStream(data));
      if (image.getWidth() >= 128 && image.getHeight() >= 128) return data;
      var canvas =
          new java.awt.image.BufferedImage(
              Math.max(128, image.getWidth()),
              Math.max(128, image.getHeight()),
              java.awt.image.BufferedImage.TYPE_INT_RGB);
      var graphics = canvas.createGraphics();
      try {
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        graphics.drawImage(
            image,
            (canvas.getWidth() - image.getWidth()) / 2,
            (canvas.getHeight() - image.getHeight()) / 2,
            null);
      } finally {
        graphics.dispose();
      }
      var out = new ByteArrayOutputStream();
      ImageIO.write(canvas, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new Problem(503, "ASSET_UNAVAILABLE", "Chưa chuẩn bị được ảnh garment.");
    }
  }

  /** Đọc object key công khai trong seed SQL; host cố định từ cấu hình, không nhận URL client. */
  public byte[] catalogBytes(String key) {
    if (key != null && key.matches("[a-f0-9-]{32,36}")) return bytes(key);
    Problem.require(key != null && key.length() <= 500
        && (key.startsWith("catalog/products/") || key.startsWith("catalog-processing/products/") || key.startsWith("avatars/"))
        && key.matches("[A-Za-z0-9_./-]+")
        && Arrays.stream(key.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")),
        422, "CATALOG_KEY_INVALID", "Object key catalog không hợp lệ.");

    // Kiểm tra cache local trên đĩa trước để phản hồi siêu tốc (<1ms)
    String cacheFileName = Idempotency.hash(key) + ".png";
    Path cacheFile = cacheDir.resolve(cacheFileName);
    if (Files.isRegularFile(cacheFile)) {
      try {
        return Files.readAllBytes(cacheFile);
      } catch (IOException ignored) {}
    }

    Problem.require(!catalogBase.isBlank(), 503, "CATALOG_STORAGE_NOT_CONFIGURED", "Cần cấu hình R2_PUBLIC_BASE_URL cho ảnh seed SQL.");
    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(catalogBase + "/" + key))
        .header("User-Agent", "Java-HttpClient/21")
        .timeout(java.time.Duration.ofSeconds(45)).GET().build();
    var pending = catalogHttp.sendAsync(request, info -> new BoundedImageBody(64 * 1024 * 1024));
    try {
      // Deadline gồm cả body, không chỉ khoảng chờ response headers.
      var response = pending.get(45, java.util.concurrent.TimeUnit.SECONDS);
      Problem.require(response.statusCode() == 200, 503, "CATALOG_IMAGE_UNAVAILABLE", "Ảnh catalog chưa có trên object storage.");
      byte[] data = response.body();
      byte[] resultBytes;
      // Kiểm tra header trước decode, chỉ proxy ảnh thật và bỏ metadata khi xuất PNG.
      try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
        var readers = ImageIO.getImageReaders(stream);
        Problem.require(readers.hasNext(), 415, "CATALOG_IMAGE_INVALID", "Object catalog không phải ảnh hợp lệ.");
        var reader = readers.next();
        try {
          reader.setInput(stream, true, true);
          String format = reader.getFormatName();
          int w = reader.getWidth(0), h = reader.getHeight(0);
          Problem.require((format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"))
              && (long) w * h <= 60000000,
              415, "CATALOG_IMAGE_INVALID", "Ảnh catalog sai định dạng hoặc kích thước.");
          int maxDimension = 1600;
          if (w > maxDimension || h > maxDimension) {
            var rawImage = reader.read(0);
            int targetW = w, targetH = h;
            if (targetW > targetH) {
              targetH = (int) Math.round((double) targetH * maxDimension / targetW);
              targetW = maxDimension;
            } else {
              targetW = (int) Math.round((double) targetW * maxDimension / targetH);
              targetH = maxDimension;
            }
            var scaled = new java.awt.image.BufferedImage(targetW, targetH,
                rawImage.getColorModel().hasAlpha()
                    ? java.awt.image.BufferedImage.TYPE_INT_ARGB
                    : java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = scaled.createGraphics();
            try {
              g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
              g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
              g.drawImage(rawImage, 0, 0, targetW, targetH, null);
            } finally {
              g.dispose();
            }
            var out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", out);
            resultBytes = out.toByteArray();
          } else
          if (format.equalsIgnoreCase("png")) {
            resultBytes = data;
          } else {
            var out = new ByteArrayOutputStream();
            ImageIO.write(reader.read(0), "png", out);

            resultBytes = out.toByteArray();
          }
        } finally { reader.dispose(); }
      }
      try {
        Files.write(cacheFile, resultBytes);
      } catch (IOException ignored) {}
      return resultBytes;
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new Problem(503, "CATALOG_IMAGE_UNAVAILABLE", "Tải ảnh catalog bị gián đoạn.");
    } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
      pending.cancel(true);
      throw new Problem(503, "CATALOG_IMAGE_UNAVAILABLE", "Tải ảnh catalog quá hạn hoặc vượt giới hạn.");
    } catch (IOException e) {
      throw new Problem(503, "CATALOG_IMAGE_UNAVAILABLE", "Không tải được ảnh catalog.");
    }
  }

  /** Subscriber hủy upstream ngay khi vượt cap, không đệm body không giới hạn trong RAM. */
  private static class BoundedImageBody implements java.net.http.HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
    private java.util.concurrent.Flow.Subscription subscription;
    BoundedImageBody(int limit) { this.limit = limit; }
    public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
      subscription = value;
      subscription.request(1);
    }
    public void onNext(List<java.nio.ByteBuffer> chunks) {
      for (var buffer : chunks) {
        int count = buffer.remaining();
        if (count > limit - output.size()) {
          subscription.cancel();
          result.completeExceptionally(new IOException("Image response exceeds limit"));
          return;
        }
        byte[] bytes = new byte[count];
        buffer.get(bytes);
        output.writeBytes(bytes);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(output.toByteArray()); }
  }

  /** Lưu bản kết quả trên BE để không phụ thuộc TTL asset của AI. */
  public String importResult(String owner, String role, String remote, boolean simulated) {
    var old =
        db.runtime().find("media", r -> owner.equals(r.get("OwnerId")) && remote.equals(r.get("AiAssetId")) && role.equals(r.get("RoleName")));
    for (var cached : old) {
      String file = Db.text(cached, "LocalFile");
      if (!file.isBlank() && Files.isRegularFile(root.resolve(file)))
        return Db.text(cached, "AssetId");
    }
    var asset = save(owner, role, ai.download(remote), simulated);
    String id = asset.get("assetId").toString();
    db.runtime().patch("media", id, "AiAssetId", remote);
    return id;
  }

  /** Giữ slug đã công bố; ảnh mới cùng sản phẩm dùng chung thư mục. */
  public String catalogStorageCode(String productId) {
    var product = db.one("SELECT TenSanPham,MaDanhMuc FROM SanPham WHERE MaSanPham=?", productId);
    for (var row : db.rows("SELECT DuongDanAnhGoc FROM HinhAnhSanPham WHERE MaSanPham=? ORDER BY ThuTuHienThi,MaHinhAnh", productId)) {
      var matcher = java.util.regex.Pattern.compile("catalog/products/([mw]-[A-Za-z0-9_-]+)/.*")
          .matcher(Db.text(row, "DuongDanAnhGoc"));
      if (matcher.matches()) return matcher.group(1);
    }
    String category = Db.text(product, "MaDanhMuc");
    Set<String> visited = new HashSet<>();
    while (visited.add(category)) {
      var row = db.one("SELECT MaDanhMucCha FROM DanhMuc WHERE MaDanhMuc=?", category);
      String parent = Db.text(row, "MaDanhMucCha");
      if (parent.isBlank()) break;
      category = parent;
    }
    String code = productSlug(Db.text(product, "TenSanPham"), "DM000002".equals(category));
    Problem.require(db.count("SELECT COUNT(*) FROM HinhAnhSanPham WHERE MaSanPham<>? AND DuongDanAnhGoc LIKE ?",
        productId, "catalog/products/" + code + "/%") == 0, 409, "PRODUCT_SLUG_CONFLICT",
        "Tên sản phẩm trùng thư mục ảnh của sản phẩm khác. Hãy dùng tên phân biệt.");
    return code;
  }

  public static String productSlug(String name, boolean female) {
    String slug = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
        .replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");
    Problem.require(!slug.isEmpty() && slug.length() <= 98, 422, "PRODUCT_SLUG_INVALID",
        "Tên sản phẩm cần tạo được mã lưu trữ tối đa 98 ký tự.");
    return (female ? "w-" : "m-") + slug;
  }

  /** Mọi ảnh có key riêng theo revision và thứ tự upload; retry ghi lại cùng nội dung. */
  public String publishCatalog(String product, int position, String role, String asset) {
    return publishCatalog(product, "", position, role, asset);
  }

  public synchronized String publishCatalog(String product, String analysis, int position, String role, String asset) {
    Problem.require(product != null && product.matches("[A-Za-z0-9_-]{1,100}"),
        422,"PRODUCT_ID_INVALID","Mã sản phẩm/analysis không hợp lệ.");
    String prefix = role.equals("mask") || role.equals("reference") ? "catalog-processing/products/" + product + "/" : "catalog/products/" + product + "/";
    String fileCode = role.equals("reference")
        ? String.format("--ref%02d--v01--reference.png", position)
        : role.equals("reference_original")
            ? String.format("--ref%02d--v01--original.png", position)
            : String.format("--img%02d--v01--%s.png", position, role);
    byte[] data = bytes(asset);
    String digest = Idempotency.hash(java.util.Base64.getEncoder().encodeToString(data));
    String slot = prefix + product + fileCode;
    var versions = db.runtime().find("catalog", row -> slot.equals(row.get("Slot")));
    int version = versions.stream().mapToInt(row -> ((Number) row.get("Version")).intValue()).max().orElse(0);
    String roleSuffix = role.equals("reference_original") ? "original" : role;
    String imagePrefix = prefix + product + String.format(role.startsWith("reference") ? "--ref%02d--v" : "--img%02d--v", position);
    String suffix = "--" + roleSuffix + ".png";
    var keyPattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(imagePrefix)
        + "([0-9]{2,9})" + java.util.regex.Pattern.quote(suffix));

    for (String key : r2.catalogKeys(imagePrefix)) {
      var match = keyPattern.matcher(key);
      if (match.matches()) {
        if (r2.catalogObjectMatches(key, data)) {
          saveToCache(key, data);
          db.runtime().patch("media", asset, "CatalogKey", key);
          return key;
        }
        version = Math.max(version, Integer.parseInt(match.group(1)));
      }
    }
    for (var row : db.rows("SELECT DuongDanAnhGoc AS assetKey FROM HinhAnhSanPham UNION ALL SELECT DuongDanAnhBocNen FROM HinhAnhSanPham UNION ALL SELECT DuongDanMatNaVungThayDo FROM SanPham")) {
      var match = keyPattern.matcher(Db.text(row, "assetKey"));
      if (match.matches()) version = Math.max(version, Integer.parseInt(match.group(1)));
    }
    version++;
    String key = imagePrefix + String.format("%02d", version) + suffix;
    Problem.require(r2.createCatalogObject(key, data) || r2.catalogObjectMatches(key, data),
        409, "CATALOG_IMAGE_WRITE_CONFLICT", "Ảnh đang được đồng bộ. Hãy thử lưu lại.");
    db.runtime().put("catalog", Idempotency.hash(key),
        Map.of("Slot", slot, "Key", key, "Digest", digest, "Version", version));
    saveToCache(key, data);
    db.runtime().patch("media", asset, "CatalogKey", key);
    return key;
  }

  /** Chỉ xóa ảnh riêng chưa được tham chiếu; lỗi I/O không xóa bản ghi để còn thử lại. */
  @org.springframework.transaction.annotation.Transactional
  public synchronized void delete(String id, String owner) {
    var row = owned(id, owner);
    Problem.require(
        db.count(
                    "SELECT COUNT(*) FROM PhienThuDoAo WHERE DuongDanAnhNguoiDung=? OR"
                        + " DuongDanAnhKetQuaVTON=?",
                    id,
                    id)
                == 0
            && db.count(
                    "SELECT COUNT(*) FROM HinhAnhSanPham WHERE DuongDanAnhGoc=? OR"
                        + " DuongDanAnhBocNen=?",
                    id,
                    id)
                == 0
            && db.runtime().find("operations", r -> Db.text(r,"Payload").contains(id) || Db.text(r,"ResultJson").contains(id)).size()
                == 0,
        409,
        "ASSET_IN_USE",
        "Ảnh còn được dùng bởi phiên thử đồ, catalog hoặc job.");
    try {
      Files.deleteIfExists(root.resolve(Db.text(row, "LocalFile")));
      db.runtime().remove("media", id);
    } catch (IOException e) {
      throw new Problem(503, "STORAGE_UNAVAILABLE", "Chưa xóa được file ảnh.");
    }
  }

  public void saveToCache(String key, byte[] bytes) {
    if (key == null || key.isBlank() || bytes == null) return;
    try {
      Files.write(cacheDir.resolve(Idempotency.hash(key) + ".png"), bytes);
    } catch (IOException ignored) {}
  }

  public void evictCache(String key) {
    if (key == null || key.isBlank()) return;
    try {
      Files.deleteIfExists(cacheDir.resolve(Idempotency.hash(key) + ".png"));
    } catch (IOException ignored) {}
  }

  /** Đọc ảnh đại diện: local cache -> R2 private bucket -> fallback R2 public catalog (ảnh cũ). */
  public byte[] readAvatarBytes(String key) {
    Problem.require(key != null && key.length() <= 500
        && key.startsWith("avatars/")
        && key.matches("[A-Za-z0-9_./-]+")
        && Arrays.stream(key.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")),
        422, "AVATAR_KEY_INVALID", "Đường dẫn ảnh đại diện không hợp lệ.");
    Path cacheFile = cacheDir.resolve(Idempotency.hash(key) + ".png");
    if (Files.isRegularFile(cacheFile)) {
      try {
        return Files.readAllBytes(cacheFile);
      } catch (IOException ignored) {}
    }
    if (r2 != null && r2.isConfigured()) {
      byte[] data = r2.getWithCatalogFallback(key);
      try {
        Files.write(cacheFile, data);
      } catch (IOException ignored) {}
      return data;
    }
    if (!catalogBase.isBlank()) {
      return catalogBytes(key);
    }
    throw new Problem(404, "NOT_FOUND", "Không tìm thấy ảnh đại diện.");
  }

  /** Chuẩn hóa ảnh đánh giá (PNG sạch EXIF, max 1200px), lưu review-cache/ + R2 private prefix reviews/{productId}/. */
  public String saveReviewImage(String productId, String reviewId, byte[] rawBytes) {
    Problem.require(rawBytes != null && rawBytes.length > 0 && rawBytes.length <= 10 * 1024 * 1024,
        413, "FILE_TOO_LARGE", "Ảnh đánh giá không được rỗng và tối đa 10 MB.");
    String safeProduct = (productId == null ? "SP" : productId.replaceAll("[^A-Za-z0-9_-]", ""));
    String safeReview = (reviewId == null ? "DG" : reviewId.replaceAll("[^A-Za-z0-9_-]", ""));
    if (safeProduct.isBlank()) safeProduct = "SP";
    if (safeReview.isBlank()) safeReview = "DG";

    byte[] pngBytes = normalizeUploadedImage(rawBytes, 1200, "Ảnh đánh giá");
    String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String key = "reviews/" + safeProduct + "/" + safeReview + "--" + uuid + ".png";
    try {
      Files.write(reviewDir.resolve(Idempotency.hash(key) + ".png"), pngBytes);
    } catch (IOException e) {
      throw new Problem(503, "STORAGE_UNAVAILABLE", "Chưa lưu được bản sao ảnh đánh giá trên đĩa.");
    }
    if (r2 != null && r2.isConfigured()) {
      try {
        r2.upload(key, pngBytes);
      } catch (Exception e) {
        log.warn("Không upload được ảnh đánh giá {} lên R2 (giữ bản local): {}", key, e.getMessage());
      }
    }
    return key;
  }

  /** Đọc ảnh đánh giá từ review-cache/ trước, fallback tải từ R2 private bucket. */
  public byte[] readReviewImage(String key) {
    Problem.require(key != null && key.matches("reviews/[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+[.]png") && !key.contains(".."),
        422, "REVIEW_IMAGE_KEY_INVALID", "Đường dẫn ảnh đánh giá không hợp lệ.");
    Path cached = reviewDir.resolve(Idempotency.hash(key) + ".png");
    if (Files.isRegularFile(cached)) {
      try {
        return Files.readAllBytes(cached);
      } catch (IOException ignored) {}
    }
    if (r2 != null && r2.isConfigured()) {
      byte[] bytes = r2.get(key);
      try {
        Files.write(cached, bytes);
      } catch (IOException ignored) {}
      return bytes;
    }
    throw new Problem(404, "NOT_FOUND", "Không tìm thấy ảnh đánh giá.");
  }

  /** Xóa bản sao local + object R2 khi rollback tạo đánh giá thất bại. */
  public void deleteReviewImage(String key) {
    if (key == null || key.isBlank()) return;
    try {
      Files.deleteIfExists(reviewDir.resolve(Idempotency.hash(key) + ".png"));
    } catch (IOException ignored) {}
    if (r2 != null && r2.isConfigured()) {
      r2.delete(key);
    }
  }

  private byte[] normalizeUploadedImage(byte[] rawBytes, int maxDim, String label) {
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(rawBytes))) {
      var readers = ImageIO.getImageReaders(stream);
      Problem.require(readers.hasNext(), 415, "IMAGE_INVALID", label + " chỉ nhận PNG hoặc JPEG thực.");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        Problem.require(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"),
            415, "IMAGE_INVALID", label + " chỉ hỗ trợ PNG hoặc JPEG.");
        reader.setInput(stream, true, true);
        int w = reader.getWidth(0), h = reader.getHeight(0);
        Problem.require(w >= 32 && h >= 32 && (long) w * h <= 25000000,
            422, "IMAGE_DIMENSIONS_INVALID", "Cạnh ảnh tối thiểu 32px, tối đa 25 triệu pixel.");
        var image = ImageOrientation.apply(reader.read(0), ImageOrientation.read(rawBytes));
        if (image.getWidth() > maxDim || image.getHeight() > maxDim) {
          int tw = image.getWidth(), th = image.getHeight();
          if (tw > th) {
            th = (int) Math.round((double) th * maxDim / tw);
            tw = maxDim;
          } else {
            tw = (int) Math.round((double) tw * maxDim / th);
            th = maxDim;
          }
          var scaled = new java.awt.image.BufferedImage(tw, th,
              image.getColorModel().hasAlpha()
                  ? java.awt.image.BufferedImage.TYPE_INT_ARGB
                  : java.awt.image.BufferedImage.TYPE_INT_RGB);
          var g = scaled.createGraphics();
          try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, tw, th, null);
          } finally {
            g.dispose();
          }
          image = scaled;
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new Problem(415, "IMAGE_INVALID", "Không đọc được file ảnh tải lên.");
    }
  }

  /** Chuẩn hóa ảnh chat sang PNG sạch EXIF, lưu R2 private prefix chat/{owner}/ và cache local. */
  public String saveChatImage(String ownerFolder, byte[] rawBytes) {
    Problem.require(rawBytes != null && rawBytes.length > 0 && rawBytes.length <= 10 * 1024 * 1024,
        413, "FILE_TOO_LARGE", "Ảnh chat không được rỗng và tối đa 10 MB.");
    String safeOwner = (ownerFolder == null ? "guest" : ownerFolder.replaceAll("[^A-Za-z0-9_-]", ""));
    if (safeOwner.isBlank()) safeOwner = "guest";
    if (safeOwner.length() > 40) safeOwner = safeOwner.substring(0, 40);

    byte[] pngBytes = normalizeUploadedImage(rawBytes, 1600, "Ảnh chat");

    String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String key = "chat/" + safeOwner + "/msg-" + uuid + ".png";
    try {
      Files.write(chatDir.resolve(Idempotency.hash(key) + ".png"), pngBytes);
    } catch (IOException e) {
      throw new Problem(503, "STORAGE_UNAVAILABLE", "Chưa lưu được bản sao ảnh chat trên đĩa.");
    }
    if (r2 != null && r2.isConfigured()) {
      try {
        r2.upload(key, pngBytes);
      } catch (Exception e) {
        log.warn("Không upload được ảnh chat {} lên R2 (giữ bản local): {}", key, e.getMessage());
      }
    }
    return key;
  }

  /** Đọc ảnh chat từ local cache trước, fallback tải từ R2 private bucket. */
  public byte[] readChatImage(String key) {
    Problem.require(key != null && key.matches("chat/[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+[.]png") && !key.contains(".."),
        422, "CHAT_IMAGE_KEY_INVALID", "Đường dẫn ảnh chat không hợp lệ.");
    Path cached = chatDir.resolve(Idempotency.hash(key) + ".png");
    if (Files.isRegularFile(cached)) {
      try {
        return Files.readAllBytes(cached);
      } catch (IOException ignored) {}
    }
    if (r2 != null && r2.isConfigured()) {
      byte[] bytes = r2.get(key);
      try {
        Files.write(cached, bytes);
      } catch (IOException ignored) {}
      return bytes;
    }
    throw new Problem(404, "NOT_FOUND", "Không tìm thấy ảnh chat.");
  }
}
