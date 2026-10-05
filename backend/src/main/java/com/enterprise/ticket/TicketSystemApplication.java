package com.enterprise.ticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 企业内部设备借用工单系统 —— 启动类
 *
 * <p>规范依据：《企业内部设备借用工单系统_V1.1_AI全栈开发总规范》
 * <ul>
 *   <li>  技术栈：Spring Boot 3.x + JDK 21 + MyBatis-Plus + Spring Security</li>
 *   <li> 定时任务（ 启用，本阶段仅开启调度能力）</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.enterprise.ticket.module.**.mapper")
@EnableScheduling
@EnableAsync
public class TicketSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketSystemApplication.class, args);
    }
}
