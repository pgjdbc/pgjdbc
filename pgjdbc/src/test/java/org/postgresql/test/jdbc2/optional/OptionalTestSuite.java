/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Test suite for the JDBC 2.0 Optional Package implementation. This includes the DataSource,
 * ConnectionPoolDataSource, and PooledConnection implementations.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    BaseDataSourceFailoverUrlsTest.class,
    CaseOptimiserDataSourceTest.class,
    ConnectionPoolTest.class,
    PoolingDataSourceTest.class,
    SimpleDataSourceTest.class,
    SimpleDataSourceWithSetURLTest.class,
    SimpleDataSourceWithUrlTest.class,
})
public class OptionalTestSuite {

}
