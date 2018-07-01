/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util.rules;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * <p>Rule for ignore test if current version postgresql to old to use. And it necessary because
 * without it test will fail. For use it method test class or test method should be annotate with
 * {@link HaveMinimalServerVersion} annotation.</p>
 *
 * <p>Example use:
 * <pre>
 * &#064;HaveMinimalServerVersion("8.4")
 * public class CopyAPITest {
 *     &#064;Rule
 *     private ServerVersionRule versionRule = new ServerVersionRule();
 *
 *     &#064;Test
 *     public void testCopyFromFile() throws Exception {
 *         // test copy api introduce in 8.4 version
 *     }
 * }
 * </pre>
 * <pre>
 * public class LogicalReplicationTest {
 *     &#064;Rule
 *     private ServerVersionRule versionRule = new ServerVersionRule();
 *
 *     &#064;Test
 *     &#064;HaveMinimalServerVersion("9.4")
 *     public void testStartLogicalReplication() throws Exception {
 *         // test logical replication introduced in 9.4
 *     }
 * }
 * </pre>
 * </p>
 */
public class ServerVersionRule implements TestRule {
  /**
   * Server version in form x.y.z.
   */
  private final String currentDisplayVersion;
  private final Version currentVersion;

  public ServerVersionRule() {
    PgConnection connection = null;
    try {
      connection = (PgConnection) TestUtil.openDB();
      currentDisplayVersion = connection.getDBVersionNumber();
      currentVersion = ServerVersion.from(currentDisplayVersion);

    } catch (Exception e) {
      throw new IllegalStateException("Not available open connection", e);
    } finally {
      TestUtil.closeQuietly(connection);
    }
  }

  public Statement apply(final Statement base, final Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        HaveMinimalServerVersion requiredVersion =
            description.getAnnotation(HaveMinimalServerVersion.class);

        if (requiredVersion == null) {
          Class<?> testClass = description.getTestClass();
          if (testClass != null && testClass.isAnnotationPresent(HaveMinimalServerVersion.class)) {
            requiredVersion = testClass.getAnnotation(HaveMinimalServerVersion.class);
          }
        }

        if (requiredVersion != null) {
          Version version = ServerVersion.from(requiredVersion.value());
          if (version.getVersionNum() <= 0) {
            throw new IllegalArgumentException(
                "Server version " + requiredVersion.value() + " not valid for "
                    + description.getDisplayName());
          }

          if (version.getVersionNum() > currentVersion.getVersionNum()) {
            throw new AssumptionViolatedException(
                "Required for test version " + requiredVersion.value()
                    + " but current server version " + currentDisplayVersion
            );
          }
        }
        base.evaluate();
      }
    };
  }
}
