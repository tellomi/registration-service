/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.aliyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tellomi: sends verification codes through Aliyun 号码认证服务 ({@code SendSmsVerifyCode}) and checks them locally.
 *
 * <p>The code we generate is stored as the attempt's sender data; Aliyun is only used as the delivery channel (the API's
 * own {@code CheckSmsVerifyCode} is not used, so a session survives a registration-service restart only as far as the
 * session repository does). Aliyun's RPC signature is HMAC-SHA1 over the sorted, percent-encoded query (signature
 * version 1.0) — the same scheme the old Go provider used.
 */
@Singleton
@Requires(bean = AliyunSmsSenderConfiguration.class)
public class AliyunSmsVerificationCodeSender implements VerificationCodeSender {

  public static final String SENDER_NAME = "aliyun-sms";

  private static final Logger logger = LoggerFactory.getLogger(AliyunSmsVerificationCodeSender.class);
  private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
  private static final Set<String> TRANSIENT_ERROR_CODES = Set.of("isv.BUSINESS_LIMIT_CONTROL", "Throttling", "Throttling.User",
      "ServiceUnavailable", "InternalError");

  private final AliyunSmsSenderConfiguration configuration;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final Clock clock;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SecureRandom secureRandom = new SecureRandom();

  public AliyunSmsVerificationCodeSender(final AliyunSmsSenderConfiguration configuration,
      final VerificationCodeGenerator verificationCodeGenerator,
      final Clock clock) {
    this.configuration = configuration;
    this.verificationCodeGenerator = verificationCodeGenerator;
    this.clock = clock;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  @Override
  public String getName() {
    return SENDER_NAME;
  }

  @Override
  public Duration getAttemptTtl() {
    return configuration.sessionTtl();
  }

  @Override
  public boolean supportsTransport(final MessageTransport transport) {
    return transport == MessageTransport.SMS;
  }

  @Override
  public boolean supportsLanguage(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges) {
    // The message text comes from the (Chinese) Aliyun template, whatever the client's language.
    return messageTransport == MessageTransport.SMS;
  }

  @Override
  public AttemptData sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws SenderRejectedRequestException {

    if (messageTransport != MessageTransport.SMS) {
      throw new SenderRejectedRequestException("aliyun-sms only supports SMS");
    }

    final String verificationCode = verificationCodeGenerator.generateVerificationCode();
    final String templateParam;
    try {
      templateParam = objectMapper.writeValueAsString(Map.of("code", verificationCode, "min", configuration.templateMinutes()));
    } catch (final IOException e) {
      throw new SenderRejectedRequestException("could not build template params");
    }

    final TreeMap<String, String> params = new TreeMap<>();
    params.put("Action", "SendSmsVerifyCode");
    params.put("Version", "2017-05-25");
    params.put("Format", "JSON");
    params.put("CountryCode", Integer.toString(phoneNumber.getCountryCode()));
    params.put("PhoneNumber", Long.toString(phoneNumber.getNationalNumber()));
    params.put("SignName", configuration.signName());
    params.put("TemplateCode", configuration.templateCode());
    params.put("TemplateParam", templateParam);
    params.put("AccessKeyId", configuration.accessKeyId());
    params.put("SignatureMethod", "HMAC-SHA1");
    params.put("SignatureVersion", "1.0");
    params.put("SignatureNonce", Long.toUnsignedString(secureRandom.nextLong()));
    params.put("Timestamp", TIMESTAMP_FORMAT.format(clock.instant().atZone(java.time.ZoneOffset.UTC)));
    params.put("Signature", sign("POST", params, configuration.accessKeySecret()));

    final String form = params.entrySet().stream()
        .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
        .collect(Collectors.joining("&"));

    final HttpResponse<String> response;
    try {
      response = httpClient.send(HttpRequest.newBuilder(URI.create(configuration.endpoint() + "/"))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (final IOException | InterruptedException e) {
      logger.warn("aliyun-sms: request failed", e);
      throw new SenderRejectedRequestException("aliyun-sms: request failed");
    }

    final String code;
    final String message;
    final String requestId;
    try {
      final JsonNode body = objectMapper.readTree(response.body());
      code = body.path("Code").asText("");
      message = body.path("Message").asText("");
      requestId = body.path("RequestId").asText("");
    } catch (final IOException e) {
      throw new SenderRejectedRequestException("aliyun-sms: unparseable response (HTTP " + response.statusCode() + ")");
    }

    if (!"OK".equals(code)) {
      // Never log the phone number; the request id is enough to look the attempt up on the Aliyun console.
      logger.warn("aliyun-sms: send rejected: code={} message={} requestId={} http={}", code, message, requestId,
          response.statusCode());
      throw new SenderRejectedRequestException(TRANSIENT_ERROR_CODES.contains(code)
          ? "aliyun-sms: temporarily unavailable (" + code + ")"
          : "aliyun-sms: " + code);
    }

    return new AttemptData(Optional.ofNullable(requestId).filter(s -> !s.isBlank()),
        verificationCode.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public boolean checkVerificationCode(final String verificationCode, final byte[] senderData) {
    return Strings.equalsConstantTime(verificationCode, new String(senderData, StandardCharsets.UTF_8));
  }

  static String sign(final String method, final TreeMap<String, String> params, final String secret) {
    final String canonical = params.entrySet().stream()
        .filter(e -> !"Signature".equals(e.getKey()))
        .map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue()))
        .collect(Collectors.joining("&"));
    final String stringToSign = method + "&" + percentEncode("/") + "&" + percentEncode(canonical);
    try {
      final Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
    } catch (final java.security.GeneralSecurityException e) {
      throw new AssertionError(e);
    }
  }

  /** Aliyun's RFC 3986 variant: space → %20, * → %2A, ~ left alone. */
  static String percentEncode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~");
  }
}
