/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
    TypeInfoCacheEdgeCaseTest.class,
    TypeInfoCacheGetPGArrayTypeCachedNameTest.class,
    TypeInfoCacheGetPGArrayTypeTest.class,
    TypeInfoCacheGetPGArrayTypeUnparseableNameTest.class,
    TypeInfoCacheGetPGArrayElementTest.class,
    TypeInfoCacheGetPGTypeByNameTest.class,
    TypeInfoCacheGetPGTypeByOidCachedNameTest.class,
    TypeInfoCacheGetPGTypeByOidTest.class,
    TypeInfoCacheGetPGTypeSearchPathTest.class,
    TypeInfoCacheGetPGTypeUnparseableNameTest.class,
    TypeInfoCacheGetSQLTypeSearchPathTest.class,
    TypeInfoCacheGetSQLTypeTest.class,
    TypeInfoCacheGetTypeForAliasTest.class
})

public class TypeInfoCacheTestSuite {
}
