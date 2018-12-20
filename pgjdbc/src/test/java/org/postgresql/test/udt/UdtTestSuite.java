/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/*
 * Executes all known tests for user-defined data types (custom types).
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        InvertedMapTest.class,
        DomainOverIntegerTest.class,
        DomainOverTextTest.class
})
public class UdtTestSuite {
}
