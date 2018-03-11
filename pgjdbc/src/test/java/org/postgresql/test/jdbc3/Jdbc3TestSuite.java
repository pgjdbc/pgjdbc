/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/*
 * Executes all known tests for JDBC3
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    Jdbc3CallableStatementTest.class,
    GeneratedKeysTest.class,
    CompositeQueryParseTest.class,
    SqlCommandParseTest.class,
    Jdbc3SavepointTest.class,
    TypesTest.class,
    ResultSetTest.class,
    ParameterMetaDataTest.class,
    Jdbc3BlobTest.class,
    DatabaseMetaDataTest.class,
    SendRecvBufferSizeTest.class,
    StringTypeParameterTest.class})
public class Jdbc3TestSuite {

}
