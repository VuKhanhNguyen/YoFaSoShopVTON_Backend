package com.yofaso.shop.ai;

import com.yofaso.shop.common.Problem;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** R2 S3: key bền vững, không lưu URL ký có thời hạn vào SQL. */
@Service
public class R2Storage implements AutoCloseable {
  private final S3Client client;
  private final String bucket;
  public R2Storage(@Value("${R2_ENDPOINT:}") String endpoint,
      @Value("${R2_ACCESS_KEY_ID:}") String access,
      @Value("${R2_SECRET_ACCESS_KEY:}") String secret,
      @Value("${R2_BUCKET_PUBLIC:yofasoshop-catalog}") String bucket) {
    this.bucket = bucket;
    if (endpoint.isBlank() || access.isBlank() || secret.isBlank()) { client=null; return; }
    var uri = URI.create(endpoint);
    if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null)
      throw new IllegalArgumentException("R2_ENDPOINT cần HTTPS không chứa credential/query");
    client=S3Client.builder().endpointOverride(uri).region(Region.of("auto"))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
      .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(30))
        .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15))).build();
  }
  public String upload(String key, byte[] bytes) {
    Problem.require(client!=null,503,"R2_NOT_CONFIGURED","Cấu hình R2 trước khi đồng bộ kết quả catalog thật.");
    Problem.require((key.matches("catalog/products/[A-Za-z0-9_/-]+[.]png") || key.matches("avatars/[A-Za-z0-9_/-]+[.]png")) && !key.contains(".."),500,"R2_KEY_INVALID","Key R2 không hợp lệ.");
    try {
      client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/png").build(),RequestBody.fromBytes(bytes));
      return key;
    } catch (Exception e) { throw new Problem(503,"R2_UPLOAD_FAILED","Chưa upload được ảnh lên R2; tác vụ sẽ đối soát lại."); }
  }
  public void delete(String key) {
    if (client == null || key == null || key.isBlank()) return;
    try {
      client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception ignored) {}
  }
  @jakarta.annotation.PreDestroy public void close() { if(client!=null) client.close(); }
}
