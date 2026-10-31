package com.yofaso.shop.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** SMTP ngoài request HTTP để không làm lộ account qua thời gian gửi; hàng đợi hữu hạn. */
@Configuration @EnableAsync
public class OtpMailConfig {
  @Bean("otpExecutor") public ThreadPoolTaskExecutor otpExecutor() {
    var executor=new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("otp-mail-");executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(10);
    return executor;
  }
}
