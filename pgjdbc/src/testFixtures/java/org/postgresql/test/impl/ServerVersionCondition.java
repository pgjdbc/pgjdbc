/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.DisabledIfServerVersionBelow;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.util.AnnotationUtils;

import java.lang.reflect.AnnotatedElement;
import java.sql.Connection;

/**
 * Evaluates condition for {@link DisabledIfServerVersionBelow} annotation.
 */
public class ServerVersionCondition implements ExecutionCondition {
  private static final ConditionEvaluationResult ENABLED = ConditionEvaluationResult.enabled(
      "@DisabledIfServerVersionBelow is not present");

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    AnnotatedElement element = context.getElement().orElse(null);
    return AnnotationUtils.findAnnotation(element, DisabledIfServerVersionBelow.class)
        .map(annotation -> this.toResult(element, annotation))
        .orElse(ENABLED);
  }

  private ConditionEvaluationResult toResult(AnnotatedElement element,
      DisabledIfServerVersionBelow annotation) {
    Version requiredVersion = ServerVersion.from(annotation.value());
    if (requiredVersion.getVersionNum() <= 0) {
      throw new IllegalArgumentException(
          "Server version " + annotation.value() + " not valid for "
              + element);
    }

    try (Connection con = TestUtil.openDB()) {
      String dbVersionNumber = con.getMetaData().getDatabaseProductVersion();
      Version actualVersion = ServerVersion.from(dbVersionNumber);
      if (requiredVersion.getVersionNum() > actualVersion.getVersionNum()) {
        return ConditionEvaluationResult.disabled(
            "Test requires version " + requiredVersion
                + ", but the server version is " + actualVersion);
      }
      return ConditionEvaluationResult.enabled(
          "Test requires version " + requiredVersion
              + ", and the server version is " + actualVersion);
    } catch (Exception e) {
      throw new IllegalStateException("Not available open connection", e);
    }
  }
}
