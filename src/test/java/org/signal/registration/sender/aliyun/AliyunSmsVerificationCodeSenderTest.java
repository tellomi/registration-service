/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.aliyun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.VerificationCodeGenerator;

class AliyunSmsVerificationCodeSenderTest {

  @Test
  void percentEncode() {
    assertEquals("a%20b%2Ac~d%2F%7B%22code%22%3A%22123456%22%7D",
        AliyunSmsVerificationCodeSender.percentEncode("a b*c~d/{\"code\":\"123456\"}"));
  }

  @Test
  void signMatchesAliyunReferenceVector() {
    // Reference vector computed independently in Python (hmac/sha1 over the same canonical string); the Go provider in
    // legacy/flutter-go (smsprov/aliyun.go) uses the identical scheme.
    final TreeMap<String, String> params = new TreeMap<>();
    params.put("Action", "SendSmsVerifyCode");
    params.put("AccessKeyId", "testid");
    params.put("Format", "JSON");
    params.put("SignatureMethod", "HMAC-SHA1");
    params.put("SignatureNonce", "nonce");
    params.put("SignatureVersion", "1.0");
    params.put("Timestamp", "2026-09-21T00:00:00Z");
    params.put("Version", "2017-05-25");
    params.put("TemplateParam", "{\"code\":\"123456\",\"min\":\"5\"}");
    assertEquals("t/i6U78cAv0Gwly+TzqbcCpbGak=", AliyunSmsVerificationCodeSender.sign("POST", params, "testsecret"));
  }

  @Test
  void checkVerificationCodeIsExactMatch() {
    final AliyunSmsVerificationCodeSender sender = new AliyunSmsVerificationCodeSender(
        new AliyunSmsSenderConfiguration("id", "secret", "sign", "100001", null, null, Duration.ofMinutes(10)),
        new VerificationCodeGenerator(), Clock.systemUTC());
    assertTrue(sender.supportsTransport(MessageTransport.SMS));
    assertFalse(sender.supportsTransport(MessageTransport.VOICE));
    assertTrue(sender.checkVerificationCode("123456", "123456".getBytes(StandardCharsets.UTF_8)));
    assertFalse(sender.checkVerificationCode("123457", "123456".getBytes(StandardCharsets.UTF_8)));
    assertEquals("aliyun-sms", sender.getName());
    assertEquals("https://dypnsapi.aliyuncs.com", new AliyunSmsSenderConfiguration("i", "s", "n", "t", null, null, null).endpoint());
  }
}
