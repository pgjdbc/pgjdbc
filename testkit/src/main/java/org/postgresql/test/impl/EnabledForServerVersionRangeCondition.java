/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.util.AnnotationUtils;

import java.lang.reflect.AnnotatedElement;
import java.sql.Connection;

/**
 * Evaluates condition for {@link EnabledForServerVersionRange} annotation.
 */
public class EnabledForServerVersionRangeCondition implements ExecutionCondition {
  private static final ConditionEvaluationResult ENABLED = ConditionEvaluationResult.enabled(
      "@EnabledForServerVersionRange is not present");

  private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(EnabledForServerVersionRangeCondition.class);
  private static final String STORE_KEY = "serverVersionNum";

  private static Version getServerVersion(ExtensionContext context) {
    // Use the root store so the version is computed once per entire test run
    ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);
    return store.getOrComputeIfAbsent(
        STORE_KEY,
        key -> computeServerVersionNum(),
        Version.class
    );
  }

  private static Version computeServerVersionNum() {
    try (Connection con = TestUtil.openDB()) {
      String dbVersionNumber = con.getMetaData().getDatabaseProductVersion();
      return ServerVersion.from(dbVersionNumber);
    } catch (Exception e) {
      throw new IllegalStateException("No available open connection", e);
    }
  }

  private static @Nullable Version getVersion(AnnotatedElement element, String name, String value) {
    if (value == null || value.equals("")) {
      return null;
    }
    Version version = ServerVersion.from(value);
    if (version.getVersionNum() <= 0) {
      throw new IllegalArgumentException(
        "Server " + name + " version " + value + " is not valid for " + element);
    }
    return version;
  }

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    AnnotatedElement element = context.getElement().orElse(null);
    if (element == null) {
      return ENABLED;
    }
    EnabledForServerVersionRange annotation = AnnotationUtils.findAnnotation(element, EnabledForServerVersionRange.class).orElse(null);
    if (annotation == null) {
      return ENABLED;
    }

    // This is the server version from the database
    Version actualVersion = getServerVersion(context);
    int actualVersionNum = actualVersion.getVersionNum();

    Version lt = getVersion(element, "lt", annotation.lt());
    if (lt != null) {
      boolean matches = actualVersionNum < lt.getVersionNum();
      if (!matches) {
        return ConditionEvaluationResult.disabled("Test requires a version less than " + lt + ", but the server version is " + actualVersion);
      }
    }

    Version lte = getVersion(element, "lte", annotation.lte());
    if (lte != null) {
      boolean matches = actualVersionNum <= lte.getVersionNum();
      if (!matches) {
        return ConditionEvaluationResult.disabled("Test requires a version less than or equal " + lte + ", but the server version is " + actualVersion);
      }
    }

    Version gte = getVersion(element, "gte", annotation.gte());
    if (gte != null) {
      boolean matches = actualVersionNum >= gte.getVersionNum();
      if (!matches) {
        return ConditionEvaluationResult.disabled("Test requires a version greater than or equal " + gte + ", but the server version is " + actualVersion);
      }
    }

    Version gt = getVersion(element, "gt", annotation.gt());
    if (gt != null) {
      boolean matches = actualVersionNum > gt.getVersionNum();
      if (!matches) {
        return ConditionEvaluationResult.disabled("Test requires a version greater than " + gt + ", but the server version is " + actualVersion);
      }
    }

    return ConditionEvaluationResult.enabled("Test requires version range which matches the server version " + actualVersion);
  }
}
