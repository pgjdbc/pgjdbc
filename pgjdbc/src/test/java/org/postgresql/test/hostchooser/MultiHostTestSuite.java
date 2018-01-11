/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/*
 * Executes multi host tests (aka master/slave connectivity selection).
 */
@RunWith(Suite.class)
@Suite.SuiteClasses(MultiHostsConnectionTest.class)
public class MultiHostTestSuite {
}
