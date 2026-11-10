package com.mfb.lending.config;

import java.time.Clock;

import com.mfb.lending.common.exception.ApiErrorHttpWriter;
import com.mfb.lending.module.auth.persistence.SecurityProfileRepository;
import com.mfb.lending.module.auth.persistence.SessionRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.auth.security.JwtAuthenticationFilter;
import com.mfb.lending.module.auth.security.JwtService;
import com.mfb.lending.module.auth.security.OtpHasher;
import com.mfb.lending.module.auth.security.TotpVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AuthBeans {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public OtpHasher otpHasher(AuthProperties properties) {
        return new OtpHasher(properties.getOtp().getPepper());
    }

    @Bean
    public AesGcmCipher aesGcmCipher(AuthProperties properties) {
        return new AesGcmCipher(properties.encryptionKeyBytes());
    }

    @Bean
    public JwtService jwtService(AuthProperties properties, Clock clock) {
        return new JwtService(properties, clock);
    }

    @Bean
    public TotpVerifier totpVerifier() {
        return new TotpVerifier();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwtService,
            SessionRepository sessions,
            UserRepository users,
            SecurityProfileRepository profiles,
            ApiErrorHttpWriter writer,
            Clock clock) {
        return new JwtAuthenticationFilter(jwtService, sessions, users, profiles, writer, clock);
    }
}
