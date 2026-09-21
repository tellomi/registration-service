/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.prescribed;

import com.google.cloud.firestore.Firestore;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tellomi: prescribed verification codes from configuration instead of Firestore — the test-account mechanism for the
 * dev / staging server (numbers listed here never reach a real SMS provider). Configure as
 *
 * <pre>
 * prescribed-verification-codes:
 *   static:
 *     "+8613800000001": "123456"
 * </pre>
 */
@Singleton
@Requires(property = "prescribed-verification-codes.static")
@Requires(missingBeans = Firestore.class)
public class StaticPrescribedVerificationCodeRepository implements PrescribedVerificationCodeRepository {

  private static final Logger logger = LoggerFactory.getLogger(StaticPrescribedVerificationCodeRepository.class);

  private final Map<Phonenumber.PhoneNumber, String> verificationCodes;

  public StaticPrescribedVerificationCodeRepository(
      @io.micronaut.context.annotation.Property(name = "prescribed-verification-codes.static")
      @MapFormat(keyFormat = StringConvention.RAW) final Map<String, String> codesByE164) {

    final Map<Phonenumber.PhoneNumber, String> parsed = new HashMap<>();
    codesByE164.forEach((e164, code) -> {
      try {
        parsed.put(PhoneNumberUtil.getInstance().parse(e164, null), code);
      } catch (final NumberParseException e) {
        throw new IllegalArgumentException("prescribed-verification-codes.static: bad E.164 number: " + e164, e);
      }
    });
    this.verificationCodes = Map.copyOf(parsed);
    logger.info("Static prescribed verification codes: {} number(s)", verificationCodes.size());
  }

  @Override
  public Map<Phonenumber.PhoneNumber, String> getVerificationCodes() {
    return verificationCodes;
  }
}
