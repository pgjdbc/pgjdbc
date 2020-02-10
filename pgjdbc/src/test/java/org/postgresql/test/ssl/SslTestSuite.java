/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    CommonNameVerifierTest.class,
    LazyKeyManagerTest.class,
    LibPQFactoryHostNameTest.class,
    SslTest.class,
})
public class SslTestSuite {
}
