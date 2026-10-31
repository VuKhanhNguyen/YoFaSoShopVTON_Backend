package com.yofaso.shop.ai;

import com.yofaso.shop.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Storage local private: ảnh PNG chuẩn hóa, metadata gốc được bỏ, không dùng tên upload. */
@Service
public class MediaService {
  private final Path root;
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
    boolean source = Set.of("person", "garment", "reference").contains(role);
    if (source) db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", owner);
    Problem.require(
        bytes.length > 0 && bytes.length <= (source ? 10 : 20) * 1024 * 1024,
        413,
        "UPLOAD_TOO_LARGE",
        "Ảnh rỗng hoặc vượt 10 MB.");
    if (source)
      Problem.require(
          db.runtime().find("media", r -> owner.equals(r.get("OwnerId")) && Set.of("person","garment","reference").contains(r.get("RoleName"))).size()
              < 100,
          429,
          "ASSET_QUOTA",
          "Đã đạt giới hạn 100 ảnh nguồn cho tài khoản.");
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
            w >= (source ? 128 : 1) && h >= (source ? 128 : 1) && (long) w * h <= 20000000,
            422,
            "IMAGE_DIMENSIONS_INVALID",
            "Cạnh ảnh tối thiểu 128px, tối đa 20 triệu pixel.");
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

  private Map<String,Object> ownedRecord(String id, String owner) {
    var row = db.runtime().get("media", id);
    Problem.require(owner.equals(row.get("OwnerId")),404,"NOT_FOUND","Không tìm thấy ảnh.");
    return row;
  }

  /** Đọc file từ filename do server tạo, không từ đường dẫn client truyền. */
  public byte[] bytes(String id) {
    var row = db.runtime().get("media", id);
    Path p = root.resolve(Db.text(row, "LocalFile")).normalize();
    Problem.require(p.startsWith(root), 500, "STORAGE_INVALID", "Storage không hợp lệ.");
    try {
      return Files.readAllBytes(p);
    } catch (IOException e) {
      throw new Problem(503, "ASSET_UNAVAILABLE", "Ảnh không còn trên storage.");
    }
  }

  /** Upload ảnh đã lưu sang AI một lần rồi lưu mapping ID bền vững. */
  public String remote(String id) {
    var row = db.runtime().get("media", id);
    String remote = Db.text(row, "AiAssetId");
    if (remote.isEmpty()) {
      String role = Db.text(row, "RoleName");
      Problem.require(
          Set.of("person", "garment", "reference").contains(role),
          422,
          "ASSET_ROLE_INVALID",
          "Ảnh kết quả không được dùng sai vai trò.");
      remote = AiClient.id(Objects.toString(ai.upload(bytes(id), role).get("assetId"), ""));
      db.runtime().patch("media", id, "AiAssetId", remote);
    }
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
    if (key != null && key.matches("[a-f0-9]{32}")) return bytes(key);
    Problem.require(key != null && key.length() <= 500 && key.startsWith("catalog/products/")
        && key.matches("[A-Za-z0-9_./-]+")
        && Arrays.stream(key.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")),
        422, "CATALOG_KEY_INVALID", "Object key catalog không hợp lệ.");
    Problem.require(!catalogBase.isBlank(), 503, "CATALOG_STORAGE_NOT_CONFIGURED", "Cần cấu hình R2_PUBLIC_BASE_URL cho ảnh seed SQL.");
    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(catalogBase + "/" + key))
        .timeout(java.time.Duration.ofSeconds(15)).GET().build();
    var pending = catalogHttp.sendAsync(request, info -> new BoundedImageBody(20 * 1024 * 1024));
    try {
      // Deadline gồm cả body, không chỉ khoảng chờ response headers.
      var response = pending.get(15, java.util.concurrent.TimeUnit.SECONDS);
        Problem.require(response.statusCode() == 200, 503, "CATALOG_IMAGE_UNAVAILABLE", "Ảnh catalog chưa có trên object storage.");
        byte[] data = response.body();
        // Kiểm tra header trước decode, chỉ proxy ảnh thật và bỏ metadata khi xuất PNG.
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
          var readers = ImageIO.getImageReaders(stream);
          Problem.require(readers.hasNext(), 415, "CATALOG_IMAGE_INVALID", "Object catalog không phải ảnh hợp lệ.");
          var reader = readers.next();
          try {
            reader.setInput(stream, true, true);
            String format = reader.getFormatName();
            Problem.require((format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"))
                && (long) reader.getWidth(0) * reader.getHeight(0) <= 20000000,
                415, "CATALOG_IMAGE_INVALID", "Ảnh catalog sai định dạng hoặc kích thước.");
            var out = new ByteArrayOutputStream();
            ImageIO.write(reader.read(0), "png", out);
            Problem.require(out.size() <= 20 * 1024 * 1024, 413, "CATALOG_IMAGE_TOO_LARGE", "PNG catalog sau chuẩn hóa vượt 20 MB.");
            return out.toByteArray();
          } finally { reader.dispose(); }
        }
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
    if (!old.isEmpty()) return Db.text(old.getFirst(), "AssetId");
    var asset = save(owner, role, ai.download(remote), simulated);
    String id = asset.get("assetId").toString();
    db.runtime().patch("media", id, "AiAssetId", remote);
    return id;
  }

  /** Mọi ảnh có key riêng theo revision và thứ tự upload; retry ghi lại cùng nội dung. */
  public String publishCatalog(String product, String analysis, int position, String role, String asset) {
    Problem.require(product.matches("[A-Za-z0-9_-]{1,20}") && analysis.matches("[A-Za-z0-9_-]{1,20}"),
        422,"PRODUCT_ID_INVALID","Mã sản phẩm/analysis không hợp lệ.");
    String key="catalog/products/"+product+"/"+analysis+"/"+product+String.format("--img%02d--v01--",position)+role+".png";
    return r2.upload(key, bytes(asset));
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
}
