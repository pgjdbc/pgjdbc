/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.postgresql.PGNotification;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.Encoding;
import org.postgresql.core.Oid;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ReplicationProtocol;
import org.postgresql.core.TransactionState;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.Version;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.jdbc.FieldMetadata.Key;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.replication.PGReplicationConnection;
import org.postgresql.util.LruCache;
import org.postgresql.util.PGobject;

import org.junit.Test;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

public class PrimitiveArraySupportTest {

  public PrimitiveArraySupport<long[]> longArrays = PrimitiveArraySupport.getArraySupport(new long[] {});
  public PrimitiveArraySupport<int[]> intArrays = PrimitiveArraySupport.getArraySupport(new int[] {});
  public PrimitiveArraySupport<short[]> shortArrays = PrimitiveArraySupport.getArraySupport(new short[] {});
  public PrimitiveArraySupport<double[]> doubleArrays = PrimitiveArraySupport.getArraySupport(new double[] {});
  public PrimitiveArraySupport<float[]> floatArrays = PrimitiveArraySupport.getArraySupport(new float[] {});
  public PrimitiveArraySupport<boolean[]> booleanArrays = PrimitiveArraySupport.getArraySupport(new boolean[] {});

  @Test
  public void testLongBinary() throws Exception {
    final long[] longs = new long[84];
    for (int i = 0; i < 84; ++i) {
      longs[i] = i - 3;
    }

    final PgArray pgArray = new PgArray(null, Oid.INT8_ARRAY, longArrays.toBinaryRepresentation(null, longs));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Long[].class));

    final Long[] actual = (Long[]) arrayObj;

    assertEquals(longs.length, actual.length);

    for (int i = 0; i < longs.length; ++i) {
      assertEquals(Long.valueOf(longs[i]), actual[i]);
    }
  }

  @Test
  public void testLongToString() throws Exception {
    final long[] longs = new long[] { 12367890987L, 987664198234L, -2982470923874L };

    final String arrayString = longArrays.toArrayString(',', longs);

    assertEquals("{12367890987,987664198234,-2982470923874}", arrayString);

    final String altArrayString = longArrays.toArrayString(';', longs);

    assertEquals("{12367890987;987664198234;-2982470923874}", altArrayString);
  }

  @Test
  public void testIntBinary() throws Exception {
    final int[] ints = new int[13];
    for (int i = 0; i < 13; ++i) {
      ints[i] = i - 3;
    }

    final PgArray pgArray = new PgArray(null, Oid.INT4_ARRAY, intArrays.toBinaryRepresentation(null, ints));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Integer[].class));

    final Integer[] actual = (Integer[]) arrayObj;

    assertEquals(ints.length, actual.length);

    for (int i = 0; i < ints.length; ++i) {
      assertEquals(Integer.valueOf(ints[i]), actual[i]);
    }
  }

  @Test
  public void testIntToString() throws Exception {
    final int[] ints = new int[] { 12367890, 987664198, -298247092 };

    final String arrayString = intArrays.toArrayString(',', ints);

    assertEquals("{12367890,987664198,-298247092}", arrayString);

    final String altArrayString = intArrays.toArrayString(';', ints);

    assertEquals("{12367890;987664198;-298247092}", altArrayString);

  }

  @Test
  public void testShortToBinary() throws Exception {
    final short[] shorts = new short[13];
    for (int i = 0; i < 13; ++i) {
      shorts[i] = (short) (i - 3);
    }

    final PgArray pgArray = new PgArray(null, Oid.INT4_ARRAY, shortArrays.toBinaryRepresentation(null, shorts));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Short[].class));

    final Short[] actual = (Short[]) arrayObj;

    assertEquals(shorts.length, actual.length);

    for (int i = 0; i < shorts.length; ++i) {
      assertEquals(Short.valueOf(shorts[i]), actual[i]);
    }
  }

  @Test
  public void testShortToString() throws Exception {
    final short[] shorts = new short[] { 123, 34, -57 };

    final String arrayString = shortArrays.toArrayString(',', shorts);

    assertEquals("{123,34,-57}", arrayString);

    final String altArrayString = shortArrays.toArrayString(';', shorts);

    assertEquals("{123;34;-57}", altArrayString);

  }

  @Test
  public void testDoubleBinary() throws Exception {
    final double[] doubles = new double[13];
    for (int i = 0; i < 13; ++i) {
      doubles[i] = i - 3.1;
    }

    final PgArray pgArray = new PgArray(null, Oid.FLOAT8_ARRAY, doubleArrays.toBinaryRepresentation(null, doubles));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Double[].class));

    final Double[] actual = (Double[]) arrayObj;

    assertEquals(doubles.length, actual.length);

    for (int i = 0; i < doubles.length; ++i) {
      assertEquals(Double.valueOf(doubles[i]), actual[i]);
    }
  }

  @Test
  public void testdoubleToString() throws Exception {
    final double[] doubles = new double[] { 122353.345, 923487.235987, -23.239486 };

    final String arrayString = doubleArrays.toArrayString(',', doubles);

    assertEquals("{\"122353.345\",\"923487.235987\",\"-23.239486\"}", arrayString);

    final String altArrayString = doubleArrays.toArrayString(';', doubles);

    assertEquals("{\"122353.345\";\"923487.235987\";\"-23.239486\"}", altArrayString);

  }

  @Test
  public void testFloatBinary() throws Exception {
    final float[] floats = new float[13];
    for (int i = 0; i < 13; ++i) {
      floats[i] = (float) (i - 3.1);
    }

    final PgArray pgArray = new PgArray(null, Oid.FLOAT4_ARRAY, floatArrays.toBinaryRepresentation(null, floats));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Float[].class));

    final Float[] actual = (Float[]) arrayObj;

    assertEquals(floats.length, actual.length);

    for (int i = 0; i < floats.length; ++i) {
      assertEquals(Float.valueOf(floats[i]), actual[i]);
    }
  }

  @Test
  public void testfloatToString() throws Exception {
    final float[] floats = new float[] { 122353.34f, 923487.25f, -23.2394f };

    final String arrayString = floatArrays.toArrayString(',', floats);

    assertEquals("{\"122353.34\",\"923487.25\",\"-23.2394\"}", arrayString);

    final String altArrayString = floatArrays.toArrayString(';', floats);

    assertEquals("{\"122353.34\";\"923487.25\";\"-23.2394\"}", altArrayString);

  }

  @Test
  public void testBooleanBinary() throws Exception {
    final boolean[] bools = new boolean[] { true, true, false };

    final PgArray pgArray = new PgArray(null, Oid.BIT, booleanArrays.toBinaryRepresentation(null, bools));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Boolean[].class));

    final Boolean[] actual = (Boolean[]) arrayObj;

    assertEquals(bools.length, actual.length);

    for (int i = 0; i < bools.length; ++i) {
      assertEquals(Boolean.valueOf(bools[i]), actual[i]);
    }
  }

  @Test
  public void testBooleanToString() throws Exception {
    final boolean[] bools = new boolean[] { true, true, false };

    final String arrayString = booleanArrays.toArrayString(',', bools);

    assertEquals("{1,1,0}", arrayString);

    final String altArrayString = booleanArrays.toArrayString(';', bools);

    assertEquals("{1;1;0}", altArrayString);
  }

  @Test
  public void testStringBinary() throws Exception {
    final PrimitiveArraySupport<String[]> stringArrays = PrimitiveArraySupport.getArraySupport(new String[] {});
    //unicode escape for pi character
    final String[] strings = new String[] {"1.235", null, "", "have some \u03C0"};
    final BaseConnection conn = new UTFConnection();
    final PgArray pgArray = new PgArray(conn, Oid.VARCHAR, stringArrays.toBinaryRepresentation(conn, strings));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(String[].class));

    final String[] actual = (String[]) arrayObj;

    assertEquals(strings.length, actual.length);

    for (int i = 0; i < strings.length; ++i) {
      assertEquals(strings[i], actual[i]);
    }
  }

  @Test
  public void testStringToString() throws Exception {
    final PrimitiveArraySupport<String[]> stringArrays = PrimitiveArraySupport.getArraySupport(new String[] {});
    //unicode escape for pi character
    final String[] strings = new String[] {"1.235", null, "", "have some \u03C0"};

    final String arrayString = stringArrays.toArrayString(',', strings);

    assertEquals("{\"1.235\",NULL,\"\",\"have some \u03C0\"}", arrayString);
  }

  private static final class UTFConnection implements BaseConnection
  {
    UTFConnection()
    {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Encoding getEncoding() throws SQLException {
      return Encoding.getJVMEncoding("UTF-8");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancelQuery() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet execSQLQuery(String s) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execSQLUpdate(String s) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueryExecutor getQueryExecutor() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ReplicationProtocol getReplicationProtocol() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getObject(String type, String value, byte[] byteValue) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TypeInfo getTypeInfo() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean haveMinimumServerVersion(int ver) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean haveMinimumServerVersion(Version ver) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] encodeString(String str) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String escapeString(String str) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getStandardConformingStrings() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TimestampUtils getTimestampUtils() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Logger getLogger() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getStringVarcharFlag() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TransactionState getTransactionState() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean binaryTransferSend(int oid) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isColumnSanitiserDisabled() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addTimerTask(TimerTask timerTask, long milliSeconds) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeTimerTasks() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LruCache<Key, FieldMetadata> getFieldMetadataCache() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CachedQuery createQuery(String sql, boolean escapeProcessing, boolean isParameterized,
        String... columnNames) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFlushCacheOnDeallocate(boolean flushCacheOnDeallocate) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement createStatement() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String nativeSQL(String sql) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getAutoCommit() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void commit() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rollback() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isClosed() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOnly() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCatalog(String catalog) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCatalog() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getTransactionIsolation() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SQLWarning getWarnings() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearWarnings() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
        int resultSetConcurrency) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHoldability(int holdability) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getHoldability() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Savepoint setSavepoint() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType,
        int resultSetConcurrency, int resultSetHoldability) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Clob createClob() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Blob createBlob() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NClob createNClob() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SQLXML createSQLXML() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isValid(int timeout) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getClientInfo(String name) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Properties getClientInfo() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSchema(String schema) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSchema() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void abort(Executor executor) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNetworkTimeout() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Array createArrayOf(String typeName, Object elements) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PGNotification[] getNotifications() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PGNotification[] getNotifications(int timeoutMillis) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CopyManager getCopyAPI() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public LargeObjectManager getLargeObjectAPI() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Fastpath getFastpathAPI() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDataType(String type, String className) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDataType(String type, Class<? extends PGobject> klass) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setPrepareThreshold(int threshold) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getPrepareThreshold() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDefaultFetchSize(int fetchSize) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultFetchSize() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getBackendPID() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String escapeIdentifier(String identifier) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String escapeLiteral(String literal) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PreferQueryMode getPreferQueryMode() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AutoSave getAutosave() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAutosave(AutoSave autoSave) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PGReplicationConnection getReplicationAPI() {
      throw new UnsupportedOperationException();
    }
    
  }
}
