/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.DisabledIfServerVersionGreater;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.lang.reflect.AnnotatedElement;
import java.sql.Connection;

public class ServerVersionGreaterCondition implements ExecutionCondition {
  private static final ConditionEvaluationResult ENABLED = ConditionEvaluationResult.enabled(
      "@DisabledIfServerVersionGreater is not present");

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    return context.getElement()
            .flatMap(element ->
                AnnotationSupport.findAnnotation(element, DisabledIfServerVersionGreater.class)
                            .map(annotation -> ServerVersionGreaterCondition.toResult(element, annotation))
            ).orElse(ENABLED);
  }

  private static ConditionEvaluationResult toResult(AnnotatedElement element,
      DisabledIfServerVersionGreater annotation) {
    Version maxVersion = ServerVersion.from(annotation.value());
    if (maxVersion.getVersionNum() <= 0) {
      throw new IllegalArgumentException(
          "Server version " + annotation.value() + " not valid for "
              + element);
    }

    try (Connection con = TestUtil.openDB()) {
      String dbVersionNumber = con.getMetaData().getDatabaseProductVersion();
      Version actualVersion = ServerVersion.from(dbVersionNumber);
      if (actualVersion.getVersionNum() >= maxVersion.getVersionNum()) {
        return ConditionEvaluationResult.disabled(
            "Test requires version <= " + maxVersion
                + ", but the server version is " + actualVersion);
      }
      return ConditionEvaluationResult.enabled(
          "Test requires version <= " + maxVersion
              + ", and the server version is " + actualVersion);
    } catch (Exception e) {
      throw new IllegalStateException("Not available open connection", e);
    }
  }
}
