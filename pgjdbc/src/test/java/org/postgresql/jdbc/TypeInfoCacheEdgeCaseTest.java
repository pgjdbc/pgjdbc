/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

public class TypeInfoCacheEdgeCaseTest extends BaseTest4 {
  @Rule
  public final ExpectedException exception = ExpectedException.none();

  private TypeInfo typeInfo;

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.UNKNOWN_LENGTH.set(props, UNKNOWN_LENGTH);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    typeInfo = ((PgConnection) con).getTypeInfo();
  }

  private static final String NO_SUCH_TYPE_NAME = "none.such";
  private static final int NO_SUCH_TYPE_OID = -100;
  private static final int UNKNOWN_LENGTH = 100000;

  @Test
  public void testGetArrayTypeDelimiter() throws SQLException {
    assertEquals("Oid.UNSPECIFIED", ",",
        String.valueOf(typeInfo.getArrayDelimiter(Oid.UNSPECIFIED)));
  }

  @Test
  public void testGetArrayTypeDelimiterThrowsOnNonExistentOid() throws SQLException {
    exception.expect(PSQLException.class);
    typeInfo.getArrayDelimiter(NO_SUCH_TYPE_OID);
  }

  @Test
  public void testDisplaySize() {
    assertEquals("Oid.UNSPECIFIED", UNKNOWN_LENGTH, typeInfo.getDisplaySize(Oid.UNSPECIFIED, 0));
    assertEquals("non-existent type", UNKNOWN_LENGTH, typeInfo.getDisplaySize(NO_SUCH_TYPE_OID, 0));
  }

  @Test
  public void testGetJavaClass() throws SQLException {
    assertNull("Oid.UNSPECIFIED", typeInfo.getJavaClass(Oid.UNSPECIFIED));
    assertNull("non-existent type", typeInfo.getJavaClass(NO_SUCH_TYPE_OID));
  }

  @Test
  public void testGetPGArrayElement() throws SQLException {
    assertEquals("Oid.UNSPECIFIED", Oid.UNSPECIFIED, typeInfo.getPGArrayElement(Oid.UNSPECIFIED));
    assertEquals("non-existent type", Oid.UNSPECIFIED,
        typeInfo.getPGArrayElement(NO_SUCH_TYPE_OID));
    assertEquals("element type argument", Oid.UNSPECIFIED, typeInfo.getPGArrayElement(Oid.INT4));
  }

  @Test
  public void testGetPGArrayType() throws SQLException {
    assertEquals("null", Oid.UNSPECIFIED, typeInfo.getPGArrayType(null));
    assertEquals("non-existent type", Oid.UNSPECIFIED, typeInfo.getPGArrayType(NO_SUCH_TYPE_NAME));
    assertEquals("array type argument", Oid.UNSPECIFIED, typeInfo.getPGArrayType("_int4"));
  }


  @Test
  public void testGetPGobject() {
    assertNull("null", typeInfo.getPGobject(null));
    assertNull("non-existent type", typeInfo.getPGobject(NO_SUCH_TYPE_NAME));
  }

  @Test
  public void testGetPGTypeByOid() throws SQLException {
    assertNull("Oid.UNSPECIFIED", typeInfo.getPGType(Oid.UNSPECIFIED));
    assertNull("non-existent type", typeInfo.getPGType(NO_SUCH_TYPE_OID));
  }

  @Test
  public void testGetPGTypeByName() throws SQLException {
    assertEquals("null", Oid.UNSPECIFIED, typeInfo.getPGType(null));
    assertEquals("non-existent type", Oid.UNSPECIFIED, typeInfo.getPGType(NO_SUCH_TYPE_NAME));
  }

  @Test
  public void testGetPrecision() {
    assertEquals("Oid.UNSPECIFIED", UNKNOWN_LENGTH, typeInfo.getPrecision(Oid.UNSPECIFIED, 0));
    assertEquals("non-existent type", UNKNOWN_LENGTH, typeInfo.getPrecision(NO_SUCH_TYPE_OID, 0));
  }

  @Test
  public void testGetMaximumPrecision() {
    assertEquals("Oid.UNSPECIFIED", 0, typeInfo.getMaximumPrecision(Oid.UNSPECIFIED));
    assertEquals("non-existent type", 0, typeInfo.getMaximumPrecision(NO_SUCH_TYPE_OID));
  }

  @Test
  public void testIsCaseSensitive() {
    assertTrue("Oid.UNSPECIFIED", typeInfo.isCaseSensitive(Oid.UNSPECIFIED));
    assertTrue("non-existent type", typeInfo.isCaseSensitive(NO_SUCH_TYPE_OID));
  }

  @Test
  public void testIsSigned() {
    assertFalse("Oid.UNSPECIFIED", typeInfo.isSigned(Oid.UNSPECIFIED));
    assertFalse("non-existent type", typeInfo.isSigned(NO_SUCH_TYPE_OID));
  }


  @Test
  public void testGetScale() throws SQLException {
    assertEquals("Oid.UNSPECIFIED", 0, typeInfo.getScale(Oid.UNSPECIFIED, 0));
    assertEquals("non-existent type", 0, typeInfo.getScale(NO_SUCH_TYPE_OID, 0));
  }

  @Test
  public void testGetSQLTypeByName() throws SQLException {
    assertEquals("null", Types.OTHER, typeInfo.getSQLType(null));
    assertEquals("non-existent type", Types.OTHER, typeInfo.getSQLType(NO_SUCH_TYPE_NAME));
  }

  @Test
  public void testGetSQLTypeByOid() throws SQLException {
    assertEquals("Oid.UNSPECIFIED", Types.OTHER, typeInfo.getSQLType(Oid.UNSPECIFIED));
    assertEquals("non-existent type", Types.OTHER, typeInfo.getSQLType(NO_SUCH_TYPE_OID));
  }

  @Test
  public void testGetTypeForAlias() throws SQLException {
    assertNull("null", typeInfo.getTypeForAlias(null));
  }

  @Test
  public void testRequiresQuoting() throws SQLException {
    assertTrue("Oid.UNSPECIFIED", typeInfo.requiresQuoting(Oid.UNSPECIFIED));
    assertTrue("non-existent type", typeInfo.requiresQuoting(NO_SUCH_TYPE_OID));
  }

  @Test
  public void testRequiresQuotingSQLType() throws SQLException {
    assertTrue(typeInfo.requiresQuotingSqlType(Types.OTHER));
  }

}
