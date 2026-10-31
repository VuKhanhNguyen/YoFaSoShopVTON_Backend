package com.yofaso.shop.ai;

import com.yofaso.shop.common.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** Client nội bộ: URL/key từ cấu hình, không nhận host/path/URL do người dùng đưa. */
@Component
public class AiClient {
  private final RestClient client;
  private final String key;

  /** Cố định timeout kết nối/read, không tự retry POST để tránh sinh job trùng. */
  public AiClient(
      @Value("${app.ai.base-url}") String base,
      @Value("${app.ai.key}") String key,
      @Value("${app.ai.connect-timeout-seconds}") int connect,
      @Value("${app.ai.read-timeout-seconds}") int read) {
    URI uri = URI.create(base);
    Problem.require(
        Set.of("http", "https").contains(uri.getScheme())
            && uri.getHost() != null
            && uri.getUserInfo() == null
            && uri.getQuery() == null,
        500,
        "AI_CONFIG_INVALID",
        "Địa chỉ AI không hợp lệ.");
    this.key = key;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connect))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(Duration.ofSeconds(read));
    client =
        RestClient.builder()
            .baseUrl(base)
            .requestFactory(factory)
            .defaultHeader("X-Internal-API-Key", key)
            .requestInterceptor(
                (request, body, execution) -> {
                  String id = MDC.get("correlationId");
                  if (id != null) request.getHeaders().set("X-Correlation-ID", id);
                  return execution.execute(request, body);
                })
            .build();
  }

  /** Chỉ nhận ID do AI trả về đúng dạng hợp đồng, chặn chèn đường dẫn. */
  public static String id(String value) {
    Problem.require(
        value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"),
        502,
        "AI_RESPONSE_INVALID",
        "AI trả ID không hợp lệ.");
    return value;
  }

  /** Gửi JSON tới endpoint nội bộ định sẵn. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> post(String path, Object body) {
    configured();
    try {
      return client
          .post()
          .uri(path)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(Map.class);
    } catch (RestClientException e) {
      throw error(e);
    }
  }

  /** Poll/tìm lại job bằng ID nghiệp vụ khi submit bị timeout. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> get(String path) {
    configured();
    try {
      return client.get().uri(path).retrieve().body(Map.class);
    } catch (RestClientException e) {
      throw error(e);
    }
  }

  /** Forward file PNG sạch cùng role, tuyệt đối không forward tên/path gốc. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> upload(byte[] bytes, String role) {
    configured();
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("role", role);
    parts.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "upload.png";
          }
        });
    try {
      return client
          .post()
          .uri("/internal/v1/assets")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(parts)
          .retrieve()
          .body(Map.class);
    } catch (RestClientException e) {
      throw error(e);
    }
  }

  /** Download chỉ bằng assetId, giới hạn dữ liệu phản hồi để tránh hết RAM. */
  public byte[] download(String asset) {
    configured();
    try {
      return client
          .get()
          .uri("/internal/v1/assets/" + id(asset) + "/content")
          .exchange(
              (q, r) -> {
                if (!r.getStatusCode().is2xxSuccessful())
                  throw new Problem(502, "AI_ASSET_UNAVAILABLE", "Không tải được kết quả AI.");
                byte[] bytes = r.getBody().readNBytes(20 * 1024 * 1024 + 1);
                Problem.require(
                    bytes.length <= 20 * 1024 * 1024,
                    502,
                    "AI_RESPONSE_TOO_LARGE",
                    "Ảnh AI vượt giới hạn.");
                return bytes;
              });
    } catch (RestClientException e) {
      throw error(e);
    }
  }

  /** Báo thiếu key khi dùng AI, vẫn cho phép chức năng bán hàng khởi động. */
  private void configured() {
    Problem.require(
        key.length() >= 32 && !key.startsWith("CHANGE"),
        503,
        "AI_NOT_CONFIGURED",
        "Chưa cấu hình key kết nối AI.");
  }

  /** Lỗi upstream không echo secret/body nội bộ; 404 được dùng cho reconcile session. */
  private Problem error(RestClientException e) {
    if (e instanceof RestClientResponseException r) {
      int status = r.getStatusCode().value();
      return new Problem(
          status == 404 ? 404 : status == 401 ? 503 : status >= 500 ? 503 : status,
          "AI_HTTP_" + status,
          status == 404
              ? "Chưa tìm thấy tài nguyên AI."
              : "AI từ chối hoặc chưa xử lý được request; kiểm tra ảnh, trạng thái và cấu hình.");
    }
    return new Problem(503, "AI_UNAVAILABLE", "Không kết nối được AI; kiểm tra dịch vụ/tunnel.");
  }
}
