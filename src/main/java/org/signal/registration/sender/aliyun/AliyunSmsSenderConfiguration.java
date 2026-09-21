/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.aliyun;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;

/**
 * Tellomi: Aliyun 号码认证服务（dypnsapi, SendSmsVerifyCode）— the SMS channel that actually works for mainland-China numbers.
 *
 * <p>Enabled when {@code aliyun-sms.access-key-id} is set. {@code template-code} is the (gifted) verification template; its
 * variables are {@code code} and {@code min}. {@code endpoint} defaults to the public dypnsapi endpoint.
 */
@ConfigurationProperties("aliyun-sms")
@Requires(property = "aliyun-sms.access-key-id")
public record AliyunSmsSenderConfiguration(@NotBlank String accessKeyId,
                                           @NotBlank String accessKeySecret,
                                           @NotBlank String signName,
                                           @NotBlank String templateCode,
                                           @Nullable String templateMinutes,
                                           @Nullable String endpoint,
                                           @Nullable Duration sessionTtl) {

  public AliyunSmsSenderConfiguration {
    if (templateMinutes == null || templateMinutes.isBlank()) {
      templateMinutes = "5";
    }
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = "https://dypnsapi.aliyuncs.com";
    }
    if (sessionTtl == null) {
      sessionTtl = Duration.ofMinutes(10);
    }
  }
}
