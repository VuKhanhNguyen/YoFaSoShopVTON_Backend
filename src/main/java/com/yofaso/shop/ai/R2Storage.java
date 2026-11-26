package com.yofaso.shop.ai;

import com.yofaso.shop.common.Problem;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** R2 S3: key bền vững, không lưu URL ký có thời hạn vào SQL. */
@Service
public class R2Storage implements AutoCloseable {
  private final S3Client client;
  private final String bucket;
  private final String privateBucket;
  public R2Storage(@Value("${R2_ENDPOINT:}") String endpoint,
      @Value("${R2_ACCESS_KEY_ID:}") String access,
      @Value("${R2_SECRET_ACCESS_KEY:}") String secret,
      @Value("${R2_BUCKET_PUBLIC:yofasoshop-catalog}") String bucket,
      @Value("${R2_BUCKET_PRIVATE:yofasoshop-private}") String privateBucket) {
    this.bucket = bucket;
    this.privateBucket = (privateBucket == null || privateBucket.isBlank()) ? bucket : privateBucket;
    if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) { client=null; return; }
    var uri = URI.create(endpoint);
    if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null)
      throw new IllegalArgumentException("R2_ENDPOINT cần HTTPS không chứa credential/query");
    client=S3Client.builder().endpointOverride(uri).region(Region.of("auto"))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
      .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(30))
        .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15))).build();
  }
  public boolean isConfigured() {
    return client != null;
  }
  public String upload(String key, byte[] bytes) {
    put(key, bytes, false);
    return key;
  }

  public boolean createCatalogObject(String key, byte[] bytes) {
    return put(key, bytes, true);
  }

  private String bucketFor(String key) {
    return (key.startsWith("catalog-processing/")
            || key.startsWith("tryon/")
            || key.startsWith("chat/")
            || key.startsWith("avatars/")
            || key.startsWith("reviews/"))
        ? privateBucket
        : bucket;
  }

  public byte[] get(String key) {
    return getFromBucket(bucketFor(key), key);
  }

  /** Đọc từ private bucket trước; nếu 404 (avatar cũ) thì tự fallback sang public catalog bucket. */
  public byte[] getWithCatalogFallback(String key) {
    Problem.require(client != null, 503, "R2_NOT_CONFIGURED", "Cấu hình R2 trước khi đọc file.");
    String primary = bucketFor(key);
    try {
      return getFromBucket(primary, key);
    } catch (Problem e) {
      if (e.status == 404 && !primary.equals(bucket)) {
        return getFromBucket(bucket, key);
      }
      throw e;
    }
  }

  private byte[] getFromBucket(String targetBucket, String key) {
    Problem.require(client != null, 503, "R2_NOT_CONFIGURED", "Cấu hình R2 trước khi đọc file.");
    try (var object = client.getObject(r -> r.bucket(targetBucket).key(key))) {
      return object.readAllBytes();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) throw new Problem(404, "NOT_FOUND", "Không tìm thấy ảnh trên R2.");
      throw new Problem(503, "R2_READ_FAILED", "Chưa kiểm tra được ảnh trên R2.");
    } catch (java.io.IOException | RuntimeException e) {
      throw new Problem(503, "R2_READ_FAILED", "Chưa kiểm tra được ảnh trên R2.");
    }
  }

  public List<String> catalogKeys(String prefix) {
    Problem.require(client != null, 503, "R2_NOT_CONFIGURED", "Cấu hình R2 trước khi đồng bộ catalog.");
    try {
      return client.listObjectsV2Paginator(r -> r.bucket(bucketFor(prefix)).prefix(prefix))
          .contents().stream().map(object -> object.key()).toList();
    } catch (RuntimeException e) {
      throw new Problem(503, "R2_READ_FAILED", "Chưa kiểm tra được ảnh catalog trên R2.");
    }
  }

  public boolean catalogObjectMatches(String key, byte[] bytes) {
    try (var object = client.getObject(r -> r.bucket(bucketFor(key)).key(key))) {
      if (object.response().contentLength() != bytes.length) {
        object.abort();
        return false;
      }
      return Arrays.equals(bytes, object.readNBytes(bytes.length + 1));
    } catch (S3Exception e) {
      if (e.statusCode() == 404) return false;
      throw new Problem(503, "R2_READ_FAILED", "Chưa kiểm tra được ảnh catalog trên R2.");
    } catch (java.io.IOException | RuntimeException e) {
      throw new Problem(503, "R2_READ_FAILED", "Chưa kiểm tra được ảnh catalog trên R2.");
    }
  }

  private boolean put(String key, byte[] bytes, boolean createOnly) {
    Problem.require(client!=null,503,"R2_NOT_CONFIGURED","Cấu hình R2 trước khi đồng bộ kết quả catalog thật.");
    Problem.require((key.matches("(catalog|catalog-processing)/products/[A-Za-z0-9_/\\-]+[.]png")
        || key.matches("avatars/[A-Za-z0-9_/\\-]+[.]png")
        || key.matches("tryon/[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+[.]png")
        || key.matches("chat/[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+[.]png")
        || key.matches("reviews/[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+[.]png")) && !key.contains(".."),500,"R2_KEY_INVALID","Key R2 không hợp lệ.");
    try {
      var request = PutObjectRequest.builder().bucket(bucketFor(key)).key(key).contentType("image/png");
      if (createOnly) request.ifNoneMatch("*");
      client.putObject(request.build(), RequestBody.fromBytes(bytes));
      return true;
    } catch (S3Exception e) {
      if (createOnly && e.statusCode() == 412) return false;
      throw new Problem(503, "R2_UPLOAD_FAILED", "Chưa upload được ảnh lên R2; tác vụ sẽ đối soát lại.");
    } catch (Exception e) { throw new Problem(503,"R2_UPLOAD_FAILED","Chưa upload được ảnh lên R2; tác vụ sẽ đối soát lại."); }
  }
  public void delete(String key) {
    if (client == null || key == null || key.isBlank()) return;
    String primary = bucketFor(key);
    try {
      client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(primary).key(key).build());
    } catch (Exception ignored) {}
    // Dọn cả bản avatar cũ nằm ở public catalog bucket nếu có
    if (key.startsWith("avatars/") && !primary.equals(bucket)) {
      try {
        client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(bucket).key(key).build());
      } catch (Exception ignored) {}
    }
  }
  @jakarta.annotation.PreDestroy public void close() { if(client!=null) client.close(); }
}
