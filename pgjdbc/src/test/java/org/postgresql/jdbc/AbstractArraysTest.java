/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGNotification;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.Encoding;
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
import org.postgresql.xml.PGXmlFactoryFactory;

import org.junit.Test;

import java.lang.reflect.Array;
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

public abstract class AbstractArraysTest<A> {

  private static final BaseConnection ENCODING_CONNECTION = new EncodingConnection(Encoding.getJVMEncoding("utf-8"));

  private final A[][] testData;

  private final boolean binarySupported;

  private final int arrayTypeOid;

  /**
   *
   * @param testData
   *          3 dimensional array to use for testing.
   * @param binarySupported
   *          Indicates if binary support is epxected for the type.
   */
  public AbstractArraysTest(A[][] testData, boolean binarySupported, int arrayTypeOid) {
    super();
    this.testData = testData;
    this.binarySupported = binarySupported;
    this.arrayTypeOid = arrayTypeOid;
  }

  protected void assertArraysEquals(String message, A expected, Object actual) {
    final int expectedLength = Array.getLength(expected);
    assertEquals(message + " size", expectedLength, Array.getLength(actual));
    for (int i = 0; i < expectedLength; ++i) {
      assertEquals(message + " value at " + i, Array.get(expected, i), Array.get(actual, i));
    }
  }

  @Test
  public void testBinary() throws Exception {

    A data = testData[0][0];

    ArrayEncoding.ArrayEncoder<A> support = ArrayEncoding.getArrayEncoder(data);

    final int defaultArrayTypeOid = support.getDefaultArrayTypeOid();

    assertEquals(binarySupported, support.supportBinaryRepresentation(defaultArrayTypeOid));

    if (binarySupported) {

      final PgArray pgArray = new PgArray(ENCODING_CONNECTION, defaultArrayTypeOid,
          support.toBinaryRepresentation(ENCODING_CONNECTION, data, defaultArrayTypeOid));

      Object actual = pgArray.getArray();

      assertArraysEquals("", data, actual);
    }
  }

  @Test
  public void testString() throws Exception {

    A data = testData[0][0];

    ArrayEncoding.ArrayEncoder<A> support = ArrayEncoding.getArrayEncoder(data);

    final String arrayString = support.toArrayString(',', data);

    final PgArray pgArray = new PgArray(ENCODING_CONNECTION, arrayTypeOid, arrayString);

    Object actual = pgArray.getArray();

    assertArraysEquals("", data, actual);
  }

  @Test
  public void test2dBinary() throws Exception {

    A[] data = testData[0];

    ArrayEncoding.ArrayEncoder<A[]> support = ArrayEncoding.getArrayEncoder(data);

    final int defaultArrayTypeOid = support.getDefaultArrayTypeOid();

    assertEquals(binarySupported, support.supportBinaryRepresentation(defaultArrayTypeOid));

    if (binarySupported) {

      final PgArray pgArray = new PgArray(ENCODING_CONNECTION, support.getDefaultArrayTypeOid(),
          support.toBinaryRepresentation(ENCODING_CONNECTION, data, defaultArrayTypeOid));

      Object[] actual = (Object[]) pgArray.getArray();

      assertEquals(data.length, actual.length);

      for (int i = 0; i < data.length; ++i) {
        assertArraysEquals("array at position " + i, data[i], actual[i]);
      }
    }
  }

  @Test
  public void test2dString() throws Exception {

    final A[] data = testData[0];

    final ArrayEncoding.ArrayEncoder<A[]> support = ArrayEncoding.getArrayEncoder(data);

    final String arrayString = support.toArrayString(',', data);

    final PgArray pgArray = new PgArray(ENCODING_CONNECTION, arrayTypeOid, arrayString);

    Object[] actual = (Object[]) pgArray.getArray();

    assertEquals(data.length, actual.length);

    for (int i = 0; i < data.length; ++i) {
      assertArraysEquals("array at position " + i, data[i], actual[i]);
    }
  }

  @Test
  public void test3dBinary() throws Exception {

    ArrayEncoding.ArrayEncoder<A[][]> support = ArrayEncoding.getArrayEncoder(testData);

    final int defaultArrayTypeOid = support.getDefaultArrayTypeOid();

    assertEquals(binarySupported, support.supportBinaryRepresentation(defaultArrayTypeOid));

    if (binarySupported) {

      final PgArray pgArray = new PgArray(ENCODING_CONNECTION, support.getDefaultArrayTypeOid(),
          support.toBinaryRepresentation(ENCODING_CONNECTION, testData, defaultArrayTypeOid));

      Object[][] actual = (Object[][]) pgArray.getArray();

      assertEquals(testData.length, actual.length);

      for (int i = 0; i < testData.length; ++i) {
        assertEquals("array length at " + i, testData[i].length, actual[i].length);
        for (int j = 0; j < testData[i].length; ++j) {
          assertArraysEquals("array at " + i + ',' + j, testData[i][j], actual[i][j]);
        }
      }
    }
  }

  @Test
  public void test3dString() throws Exception {

    final ArrayEncoding.ArrayEncoder<A[][]> support = ArrayEncoding.getArrayEncoder(testData);

    final String arrayString = support.toArrayString(',', testData);

    final PgArray pgArray = new PgArray(ENCODING_CONNECTION, arrayTypeOid, arrayString);

    Object[][] actual = (Object[][]) pgArray.getArray();

    assertEquals(testData.length, actual.length);

    for (int i = 0; i < testData.length; ++i) {
      assertEquals("array length at " + i, testData[i].length, actual[i].length);
      for (int j = 0; j < testData[i].length; ++j) {
        assertArraysEquals("array at " + i + ',' + j, testData[i][j], actual[i][j]);
      }
    }
  }

  @Test
  public void testObjectArrayCopy() throws Exception {
    final Object[] copy = new Object[testData.length];
    for (int i = 0; i < testData.length; ++i) {
      copy[i] = testData[i];
    }

    final ArrayEncoding.ArrayEncoder<A[][]> support = ArrayEncoding.getArrayEncoder(testData);
    final String arrayString = support.toArrayString(',', testData);

    final ArrayEncoding.ArrayEncoder<Object[]> copySupport = ArrayEncoding.getArrayEncoder(copy);
    final String actual = copySupport.toArrayString(',', copy);

    assertEquals(arrayString, actual);
  }

  @Test
  public void testObject2dArrayCopy() throws Exception {
    final Object[][] copy = new Object[testData.length][];
    for (int  i = 0; i < testData.length; ++i) {
      copy[i] = testData[i];
    }

    final ArrayEncoding.ArrayEncoder<A[][]> support = ArrayEncoding.getArrayEncoder(testData);
    final String arrayString = support.toArrayString(',', testData);

    final ArrayEncoding.ArrayEncoder<Object[]> copySupport = ArrayEncoding.getArrayEncoder(copy);
    final String actual = copySupport.toArrayString(',', copy);

    assertEquals(arrayString, actual);
  }

  @Test
  public void testObject3dArrayCopy() throws Exception {
    final A[][][] source = (A[][][]) Array.newInstance(testData.getClass(), 2);
    source[0] = testData;
    source[1] = testData;
    final Object[][][] copy = new Object[][][] { testData, testData };

    final ArrayEncoding.ArrayEncoder<A[][][]> support = ArrayEncoding.getArrayEncoder(source);
    final String arrayString = support.toArrayString(',', source);

    final ArrayEncoding.ArrayEncoder<Object[]> copySupport = ArrayEncoding.getArrayEncoder(copy);
    final String actual = copySupport.toArrayString(',', copy);

    assertEquals(arrayString, actual);
  }

  private static final class EncodingConnection implements BaseConnection {
    private final Encoding encoding;
    private final TypeInfo typeInfo = new TypeInfoCache(this, -1);

    EncodingConnection(Encoding encoding) {
      this.encoding = encoding;
    }

    /**
     * {@inheritDoc}
     */
    public Encoding getEncoding() throws SQLException {
      return encoding;
    }

    /**
     * {@inheritDoc}
     */
    public TypeInfo getTypeInfo() {
      return typeInfo;
    }

    /**
     * {@inheritDoc}
     */
    public void cancelQuery() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public ResultSet execSQLQuery(String s) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void execSQLUpdate(String s) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public QueryExecutor getQueryExecutor() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public ReplicationProtocol getReplicationProtocol() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Object getObject(String type, String value, byte[] byteValue) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean haveMinimumServerVersion(int ver) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean haveMinimumServerVersion(Version ver) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public byte[] encodeString(String str) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String escapeString(String str) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean getStandardConformingStrings() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public TimestampUtils getTimestampUtils() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Logger getLogger() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean getStringVarcharFlag() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public TransactionState getTransactionState() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean binaryTransferSend(int oid) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isColumnSanitiserDisabled() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void addTimerTask(TimerTask timerTask, long milliSeconds) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void purgeTimerTasks() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public LruCache<Key, FieldMetadata> getFieldMetadataCache() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public CachedQuery createQuery(String sql, boolean escapeProcessing, boolean isParameterized, String... columnNames)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setFlushCacheOnDeallocate(boolean flushCacheOnDeallocate) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Statement createStatement() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PreparedStatement prepareStatement(String sql) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public CallableStatement prepareCall(String sql) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String nativeSQL(String sql) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean getAutoCommit() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void commit() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void rollback() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void close() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isClosed() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public DatabaseMetaData getMetaData() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setReadOnly(boolean readOnly) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isReadOnly() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setCatalog(String catalog) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String getCatalog() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setTransactionIsolation(int level) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public int getTransactionIsolation() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public SQLWarning getWarnings() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void clearWarnings() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Map<String, Class<?>> getTypeMap() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setHoldability(int holdability) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public int getHoldability() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Savepoint setSavepoint() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Savepoint setSavepoint(String name) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void rollback(Savepoint savepoint) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Clob createClob() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Blob createBlob() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public NClob createNClob() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public SQLXML createSQLXML() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(int timeout) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String getClientInfo(String name) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Properties getClientInfo() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setSchema(String schema) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String getSchema() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void abort(Executor executor) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public int getNetworkTimeout() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public java.sql.Array createArrayOf(String typeName, Object elements) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PGNotification[] getNotifications() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PGNotification[] getNotifications(int timeoutMillis) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public CopyManager getCopyAPI() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public LargeObjectManager getLargeObjectAPI() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public Fastpath getFastpathAPI() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void addDataType(String type, String className) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void addDataType(String type, Class<? extends PGobject> klass) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setPrepareThreshold(int threshold) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public int getPrepareThreshold() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setDefaultFetchSize(int fetchSize) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public int getDefaultFetchSize() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public int getBackendPID() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String escapeIdentifier(String identifier) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String escapeLiteral(String literal) throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PreferQueryMode getPreferQueryMode() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public AutoSave getAutosave() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void setAutosave(AutoSave autoSave) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public PGReplicationConnection getReplicationAPI() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getParameterStatuses() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getParameterStatus(String parameterName) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PGXmlFactoryFactory getXmlFactoryFactory() throws SQLException {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hintReadOnly() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdaptiveFetch(boolean adaptiveFetch) {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getAdaptiveFetch() {
      throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean getLogServerErrorDetail() {
      return false;
    }
  }
}
