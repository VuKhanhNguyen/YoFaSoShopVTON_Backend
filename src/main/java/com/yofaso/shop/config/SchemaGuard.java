package com.yofaso.shop.config;

import com.yofaso.shop.common.Db;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Kiểm tra chỉ đọc: không sửa schema, không tạo Flyway history. */
@Component
public class SchemaGuard implements ApplicationRunner {
  private final Db db;
  private final JsonMapper json;
  public SchemaGuard(Db db, JsonMapper json) { this.db=db; this.json=json; }
  @Override @SuppressWarnings("unchecked") public void run(ApplicationArguments arguments) throws Exception {
    Map<String,List<String>> expected;
    try(var input=new ClassPathResource("db/erd-columns.json").getInputStream()) {
      expected=json.readValue(input,Map.class);
    }
    var actual=new TreeMap<String,Set<String>>(String.CASE_INSENSITIVE_ORDER);
    for(var row:db.rows("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()"))
      actual.computeIfAbsent(Db.text(row,"TABLE_NAME"),k -> new TreeSet<>()).add(Db.text(row,"COLUMN_NAME"));
    boolean matches=actual.size()==expected.size() && expected.entrySet().stream()
      .allMatch(e -> actual.containsKey(e.getKey()) && actual.get(e.getKey()).equals(new TreeSet<>(e.getValue())));
    if(!matches) {
      var diff = new StringBuilder();
      for (var entry : expected.entrySet()) {
        if (!actual.containsKey(entry.getKey())) {
          diff.append(" [Thiếu bảng: ").append(entry.getKey()).append("]");
        } else if (!actual.get(entry.getKey()).equals(new TreeSet<>(entry.getValue()))) {
          diff.append(" [Lệch cột bảng ").append(entry.getKey()).append(": expected=")
              .append(new TreeSet<>(entry.getValue())).append(", actual=").append(actual.get(entry.getKey())).append("]");
        }
      }
      for (var actKey : actual.keySet()) {
        if (!expected.containsKey(actKey)) {
          diff.append(" [Thừa bảng: ").append(actKey).append("]");
        }
      }
      throw new IllegalStateException("Schema phải đúng " + expected.size() + " bảng/cột SQL gốc (hiện tại actual=" + actual.size() + " bảng). Chi tiết:" + diff + ". Không tự migration/drop dữ liệu; xem migrationAndLocationGuide.md.");
    }
  }
}
