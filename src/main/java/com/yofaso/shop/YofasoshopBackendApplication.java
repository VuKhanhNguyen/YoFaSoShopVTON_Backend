package com.yofaso.shop;

import java.util.concurrent.Executor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class YofasoshopBackendApplication {

  @jakarta.annotation.PostConstruct
  public void init() {
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
  }

  /** Pool riêng cho tác vụ gửi Push Notification bất đồng bộ để không chặn luồng nghiệp vụ. */
  @Bean(name = "notificationExecutor")
  public Executor notificationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("notif-push-");
    executor.initialize();
    return executor;
  }

  /** Khởi động HTTP API và lịch đồng bộ job AI; không load model trong Java. */
  public static void main(String[] args) {
    SpringApplication.run(YofasoshopBackendApplication.class, args);
  }
}
