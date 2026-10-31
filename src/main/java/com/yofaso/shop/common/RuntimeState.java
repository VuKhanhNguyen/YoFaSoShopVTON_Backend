package com.yofaso.shop.common;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Trạng thái kỹ thuật ngoài ERD. Một Backend/volume; không tạo bảng SQL hay SQLite. */
@Component
public class RuntimeState implements AutoCloseable {
  public static Map<String,Object> row(Object... fields) {
    var result = new LinkedHashMap<String,Object>();
    for (int i=0;i<fields.length;i+=2) result.put(fields[i].toString(),fields[i+1]);
    return result;
  }
  private final Path root;
  private final JsonMapper json;
  private final FileChannel channel;
  private final FileLock lease;

  public RuntimeState(@Value("${app.runtime-root:./data/runtime}") String directory, JsonMapper json) throws IOException {
    root = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
    this.json = json;
    channel = FileChannel.open(root.resolve("backend.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    lease = channel.tryLock();
    if (lease == null) { channel.close(); throw new IOException("Runtime đã có Backend; mỗi instance cần volume riêng."); }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String,Object>> read(String collection) {
    if (!collection.matches("[a-z]+")) throw new IllegalArgumentException("Invalid collection");
    var file = root.resolve(collection + ".json");
    try {
      return Files.exists(file) ? json.readValue(Files.readString(file), LinkedHashMap.class) : new LinkedHashMap<>();
    } catch (Exception e) { throw new Problem(503, "RUNTIME_UNAVAILABLE", "Manifest runtime lỗi; phục hồi bản sao lưu."); }
  }

  private void write(String collection, Map<String, Map<String,Object>> data) {
    Path temp = null;
    try {
      temp = Files.createTempFile(root, collection, ".tmp");
      Files.writeString(temp, json.writeValueAsString(data));
      try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) { output.force(true); }
      Files.move(temp, root.resolve(collection + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) { throw new Problem(503, "RUNTIME_UNAVAILABLE", "Không ghi được manifest runtime."); }
    finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
  }

  public synchronized List<Map<String,Object>> find(String collection, Predicate<Map<String,Object>> condition) {
    return read(collection).values().stream().filter(condition).map(r -> (Map<String,Object>)new LinkedHashMap<>(r)).toList();
  }
  public synchronized Map<String,Object> get(String collection, String id) {
    var row = read(collection).get(id);
    if (row == null) throw new Problem(404, "NOT_FOUND", "Không tìm thấy tài nguyên runtime.");
    return new LinkedHashMap<>(row);
  }
  public synchronized void put(String collection, String id, Map<String,Object> row) {
    var data = read(collection); data.put(id, new LinkedHashMap<>(row)); write(collection, data);
  }
  /** Dùng Object... để cho phép giá trị null trong patch. */
  public synchronized void patch(String collection, Object identity, Object... fields) {
    String id=identity.toString();
    var data = read(collection); var row = data.get(id);
    if (row == null) throw new Problem(404, "NOT_FOUND", "Không tìm thấy tài nguyên runtime.");
    for (int i=0; i<fields.length; i+=2) row.put(fields[i].toString(), fields[i+1]);
    write(collection, data);
  }
  public synchronized void remove(String collection, String id) {
    var data = read(collection); data.remove(id); write(collection, data);
  }
  public synchronized void revoke(String owner) {
    var data = read("refresh"); data.values().removeIf(r -> owner.equals(r.get("MaNguoiDung"))); write("refresh", data);
  }
  public synchronized long version(String owner) {
    var row = read("auth").get(owner);
    return row == null ? 0 : ((Number)row.get("version")).longValue();
  }
  public synchronized void bump(String owner) { put("auth", owner, Map.of("version", version(owner)+1)); revoke(owner); }
  @PreDestroy public void close() throws IOException { lease.release(); channel.close(); }
}
