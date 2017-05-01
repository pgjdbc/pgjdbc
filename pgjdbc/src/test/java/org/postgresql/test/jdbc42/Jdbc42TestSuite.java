/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
    SimpleJdbc42Test.class,
    CustomizeDefaultFetchSizeTest.class,
    GetObject310Test.class,
    PreparedStatementTest.class,
    Jdbc42CallableStatementTest.class,
    GetObject310InfinityTests.class,
    SetObject310Test.class})
public class Jdbc42TestSuite {

}
