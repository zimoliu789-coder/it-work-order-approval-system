package com.enterprise.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

/**
 * 密码编码器配置（ / ）
 *
 * <p>优先 Argon2id，同时保留 BCrypt 以兼容历史哈希。哈希串带 {@code {argon2}} / {@code {bcrypt}}
 * 前缀存入 {@code users.password_hash}，便于后续平滑升级算法。
 *
 * <p>单独成类是为了切断 SecurityConfig → Filter → UserService → PasswordEncoder 的循环依赖。
 */
@Configuration
public class PasswordEncoderConfig {

    public static final String ID_ARGON2 = "argon2";
    public static final String ID_BCRYPT = "bcrypt";

    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>(4);
        // Spring Security 5.8 默认参数：saltLength=16, hashLength=32, parallelism=1, memory=4096, iterations=3
        encoders.put(ID_ARGON2, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put(ID_BCRYPT, new BCryptPasswordEncoder());

        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(ID_ARGON2, encoders);
        // 兼容不带前缀的 BCrypt 历史哈希
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
}
