/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/*
 * Executes all known tests for JDBC4.1
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    AbortTest.class,
    CloseOnCompletionTest.class,
    GetObjectTest.class,
    NetworkTimeoutTest.class,
    SchemaTest.class,
    SharedTimerClassLoaderLeakTest.class,
})
public class Jdbc41TestSuite {

}
