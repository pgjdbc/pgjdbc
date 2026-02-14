/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.postgresql.core.Version;
import org.postgresql.test.annotations.EnabledForServerVersionRange;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.util.AnnotationUtils;

import java.lang.reflect.AnnotatedElement;

/**
 * Evaluates condition for {@link EnabledForServerVersionRange} annotation.
 */
public class EnabledForServerVersionRangeCondition extends BaseServerVersionRangeCondition {
  private static final ConditionEvaluationResult ENABLED_NOT_PRESENT = ConditionEvaluationResult.enabled(
      "@EnabledForServerVersionRange is not present");

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    AnnotatedElement element = context.getElement().orElse(null);
    if (element == null) {
      return ENABLED_NOT_PRESENT;
    }
    EnabledForServerVersionRange annotation = AnnotationUtils.findAnnotation(element, EnabledForServerVersionRange.class).orElse(null);
    if (annotation == null) {
      return ENABLED_NOT_PRESENT;
    }

    // This is the server version from the database
    Version actualVersion = getServerVersion(context);

    Version lt = getVersion(element, "lt", annotation.lt());
    Version lte = getVersion(element, "lte", annotation.lte());
    Version gte = getVersion(element, "gte", annotation.gte());
    Version gt = getVersion(element, "gt", annotation.gt());

    boolean matches = matchesVersionRange(actualVersion, lt, lte, gte, gt);
    if (matches) {
      return ConditionEvaluationResult.enabled("Test is enabled for this server version " + actualVersion);
    }
    return ConditionEvaluationResult.disabled("Test is disabled for this server version " + actualVersion);
  }
}
