/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/*
 * Executes all known tests for JDBC4
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    ArrayTest.class,
    BinaryStreamTest.class,
    BinaryTest.class,
    BlobTest.class,
    CharacterStreamTest.class,
    ClientInfoTest.class,
    ConnectionValidTimeoutTest.class,
    DatabaseMetaDataHideUnprivilegedObjectsTest.class,
    DatabaseMetaDataTest.class,
    IsValidTest.class,
    JsonbTest.class,
    LogTest.class,
    PGCopyInputStreamTest.class,
    UUIDTest.class,
    WrapperTest.class,
    XmlTest.class,
})
public class Jdbc4TestSuite {
}
