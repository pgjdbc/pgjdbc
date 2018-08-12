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
        DatabaseMetaDataTest.class,
        ArrayTest.class,
        WrapperTest.class,
        BinaryTest.class,
        IsValidTest.class,
        ClientInfoTest.class,
        PGCopyInputStreamTest.class,
        BlobTest.class,
        BinaryStreamTest.class,
        CharacterStreamTest.class,
        UUIDTest.class,
        XmlTest.class
})
public class Jdbc4TestSuite {
}

