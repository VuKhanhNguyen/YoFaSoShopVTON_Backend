package com.yofaso.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class YofasoshopBackendApplication {

  @jakarta.annotation.PostConstruct
  public void init() {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
  }

  /** Khởi động HTTP API và lịch đồng bộ job AI; không load model trong Java. */
  public static void main(String[] args) {
    SpringApplication.run(YofasoshopBackendApplication.class, args);
  }
}
