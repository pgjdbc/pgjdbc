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
// TODO: Add tests for ENUM
// TODO: See what happens with a single-column compound type
// TODO: Add tests of CallableStatement
// TODO: Add tests for other types of arrays that are not supported but should be (BigDecimal, for example)
@RunWith(Suite.class)
@Suite.SuiteClasses({
        InvertedMapTest.class,
        CustomTypePriorityTest.class,
        DomainOverIntegerTest.class,
        DomainOverTextTest.class,
        DomainOverTimestampTest.class,
        SingleAttributeRequiredTest.class,
        MultipleAttributesNotAllowedTest.class,
        SQLInputWasNullBeforeReadTest.class,
        NestedObjectTest.class
})
public class UdtTestSuite {
}
