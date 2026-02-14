/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;
import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.AnnotatedElement;
import java.sql.Connection;

public abstract class BaseServerVersionRangeCondition implements ExecutionCondition {
  private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(BaseServerVersionRangeCondition.class);
  private static final String STORE_KEY = "serverVersionNum";

  protected static Version getServerVersion(ExtensionContext context) {
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

  protected static @Nullable Version getVersion(AnnotatedElement element, String name, String value) {
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

  protected static boolean matchesVersionRange(Version actualVersion, @Nullable Version lt, @Nullable Version lte, @Nullable Version gte, @Nullable Version gt) {
    if (lt == null && lte == null && gt == null && gte == null) {
      throw new IllegalArgumentException("At least one version predicate must be populated");
    }
    if (lt != null && lte != null) {
      throw new IllegalArgumentException("Both less than and less than or equal cannot be populated");
    }
    if (gt != null && gte != null) {
      throw new IllegalArgumentException("Both greater than and greater than or equal cannot be populated");
    }
    int actualVersionNum = actualVersion.getVersionNum();
    return (lt == null || actualVersionNum < lt.getVersionNum())
        && (lte == null || actualVersionNum <= lte.getVersionNum())
        && (gt == null || actualVersionNum > gt.getVersionNum())
        && (gte == null || actualVersionNum >= gte.getVersionNum());
  }
}
