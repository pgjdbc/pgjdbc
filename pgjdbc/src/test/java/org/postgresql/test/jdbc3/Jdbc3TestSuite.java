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
    CompositeQueryParseTest.class,
    CompositeTest.class,
    DatabaseMetaDataTest.class,
    EscapeSyntaxCallModeCallTest.class,
    EscapeSyntaxCallModeCallIfNoReturnTest.class,
    EscapeSyntaxCallModeSelectTest.class,
    GeneratedKeysTest.class,
    Jdbc3BlobTest.class,
    Jdbc3CallableStatementTest.class,
    Jdbc3SavepointTest.class,
    ParameterMetaDataTest.class,
    ProcedureTransactionTest.class,
    ResultSetTest.class,
    SendRecvBufferSizeTest.class,
    SqlCommandParseTest.class,
    StringTypeParameterTest.class,
    TypesTest.class,
})
public class Jdbc3TestSuite {

}
