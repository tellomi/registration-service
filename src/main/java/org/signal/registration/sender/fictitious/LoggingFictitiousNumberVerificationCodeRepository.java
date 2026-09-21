/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.fictitious;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tellomi: stand-in for the Firestore repository when no Firestore is configured. The dynamic selection strategy needs a
 * fictitious-number sender to exist; codes for fictitious numbers (+1-555-01xx, UK drama numbers, …) are only written
 * to the log, which is exactly what a dev / staging server wants.
 */
@Singleton
@Requires(missingBeans = com.google.cloud.firestore.Firestore.class)
public class LoggingFictitiousNumberVerificationCodeRepository implements FictitiousNumberVerificationCodeRepository {

  private static final Logger logger = LoggerFactory.getLogger(LoggingFictitiousNumberVerificationCodeRepository.class);

  @Override
  public void storeVerificationCode(final Phonenumber.PhoneNumber phoneNumber, final String verificationCode,
      final Duration ttl) {
    logger.info("Fictitious number {}: verification code {} (valid {})",
        PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164), verificationCode, ttl);
  }
}
