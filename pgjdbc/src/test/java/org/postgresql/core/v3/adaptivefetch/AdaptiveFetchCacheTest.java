/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.adaptivefetch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.postgresql.PGProperty;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.SqlCommand;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/**
 * Unit tests for AdaptiveFetchCache class.
 */
public class AdaptiveFetchCacheTest {

  private AdaptiveFetchCache adaptiveFetchCache;
  private int size;

  // Strings containing variables names in AdaptiveFetchCache class
  private static String infoMapVariableName = "adaptiveFetchInfoMap";
  private static String minimumSizeVariableName = "minimumAdaptiveFetchSize";
  private static String maximumSizeVariableName = "maximumAdaptiveFetchSize";
  private static String adaptiveFetchVariableName = "adaptiveFetch";
  private static String maximumBufferSizeVariableName = "maximumResultBufferSize";

  /**
   * Simple setup to create new AdaptiveFetchCache with buffer size 1000.
   */
  @Before
  public void setUp() throws SQLException {
    Properties properties = new Properties();
    size = 1000;
    adaptiveFetchCache = new AdaptiveFetchCache(size, properties);
  }

  /**
   * Tests for calling constructor with empty properties (just asserts after setUp).
   */
  @Test
  public void testConstructorDefault() throws NoSuchFieldException, IllegalAccessException {
    assertNotNull(getInfoMapVariable());
    assertEquals(size, getMaximumBufferVariable());
    assertEquals(false, getAdaptiveFetchVariable());
    assertEquals(0, getMinimumSizeVariable());
    assertEquals(-1, getMaximumSizeVariable());
  }

  /**
   * Test for calling constructor with information about adaptiveFetch property.
   */
  @Test
  public void testConstructorWithAdaptiveFetch()
      throws SQLException, NoSuchFieldException, IllegalAccessException {
    Properties properties = new Properties();
    boolean expectedValue = true;
    PGProperty.ADAPTIVE_FETCH.set(properties, expectedValue);

    adaptiveFetchCache = new AdaptiveFetchCache(size, properties);

    assertNotNull(getInfoMapVariable());
    assertEquals(size, getMaximumBufferVariable());
    assertEquals(expectedValue, getAdaptiveFetchVariable());
    assertEquals(0, getMinimumSizeVariable());
    assertEquals(-1, getMaximumSizeVariable());
  }

  /**
   * Test for calling constructor with information about adaptiveFetchMinimum property.
   */
  @Test
  public void testConstructorWithMinimumSize()
      throws SQLException, NoSuchFieldException, IllegalAccessException {
    Properties properties = new Properties();
    int expectedValue = 100;
    PGProperty.ADAPTIVE_FETCH_MINIMUM.set(properties, expectedValue);

    adaptiveFetchCache = new AdaptiveFetchCache(size, properties);

    assertNotNull(getInfoMapVariable());
    assertEquals(size, getMaximumBufferVariable());
    assertEquals(false, getAdaptiveFetchVariable());
    assertEquals(expectedValue, getMinimumSizeVariable());
    assertEquals(-1, getMaximumSizeVariable());
  }

  /**
   * Test for calling constructor with information about adaptiveFetchMaximum property.
   */
  @Test
  public void testConstructorWithMaximumSize()
      throws SQLException, NoSuchFieldException, IllegalAccessException {
    Properties properties = new Properties();
    int expectedValue = 100;
    PGProperty.ADAPTIVE_FETCH_MAXIMUM.set(properties, expectedValue);

    adaptiveFetchCache = new AdaptiveFetchCache(size, properties);

    assertNotNull(getInfoMapVariable());
    assertEquals(size, getMaximumBufferVariable());
    assertEquals(false, getAdaptiveFetchVariable());
    assertEquals(0, getMinimumSizeVariable());
    assertEquals(expectedValue, getMaximumSizeVariable());
  }

  /**
   * Test for calling constructor with information about adaptiveFetch, adaptiveFetchMinimum and
   * adaptiveFetchMaximum properties.
   */
  @Test
  public void testConstructorWithAllProperties()
      throws SQLException, NoSuchFieldException, IllegalAccessException {
    Properties properties = new Properties();
    boolean expectedAdaptiveFetchValue = false;
    int expectedMinimumSizeValue = 70;
    int expectedMaximumSizeValue = 130;
    PGProperty.ADAPTIVE_FETCH.set(properties, expectedAdaptiveFetchValue);
    PGProperty.ADAPTIVE_FETCH_MINIMUM.set(properties, expectedMinimumSizeValue);
    PGProperty.ADAPTIVE_FETCH_MAXIMUM.set(properties, expectedMaximumSizeValue);

    adaptiveFetchCache = new AdaptiveFetchCache(size, properties);

    assertNotNull(getInfoMapVariable());
    assertEquals(size, getMaximumBufferVariable());
    assertEquals(expectedAdaptiveFetchValue, getAdaptiveFetchVariable());
    assertEquals(expectedMinimumSizeValue, getMinimumSizeVariable());
    assertEquals(expectedMaximumSizeValue, getMaximumSizeVariable());
  }


  /**
   * Test for calling addNewQuery method.
   */
  @Test
  public void testAddingSingleQuery() throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = true;

    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(1, map.size());
    assertNotNull(map.get(expectedQuery));
  }

  /**
   * Test for calling addNewQuery method, but adaptiveFetch is set to false.
   */
  @Test
  public void testAddingSingleQueryWithoutAdaptiveFetch()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = false;

    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(0, map.size());
    assertNull(map.get(expectedQuery));
  }

  /**
   * Test for calling addNewQuery method twice with the same query. The query should be added only
   * once, with counter set as 2.
   */
  @Test
  public void testAddingSameQueryTwoTimes() throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = true;

    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));
    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(1, map.size());
    assertNotNull(map.get(expectedQuery));
    assertEquals(2, map.get(expectedQuery).getCounter());
  }

  /**
   * Test for calling addNewQuery method twice with the same query, but with adaptiveFetch is set to
   * false. The query shouldn't be added.
   */
  @Test
  public void testAddingSameQueryTwoTimesWithoutAdaptiveFetch()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = false;

    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));
    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(0, map.size());
    assertNull(map.get(expectedQuery));
  }

  /**
   * Test for calling addNewQuery method twice with different queries. Both queries should be
   * added.
   */
  @Test
  public void testAddingTwoDifferentQueries() throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    boolean adaptiveFetch = true;

    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));
    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery2));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(2, map.size());
    assertNotNull(map.get(expectedQuery));
    assertEquals(1, map.get(expectedQuery).getCounter());
    assertNotNull(map.get(expectedQuery2));
    assertEquals(1, map.get(expectedQuery).getCounter());
  }

  /**
   * Test for calling addNewQuery method twice with different queries, but adaptiveFetch is set to
   * false. Both queries shouldn't be added.
   */
  @Test
  public void testAddingTwoDifferentQueriesWithoutAdaptiveFetch()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    boolean adaptiveFetch = false;

    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery));
    adaptiveFetchCache.addNewQuery(adaptiveFetch, new MockUpQuery(expectedQuery2));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(0, map.size());
    assertNull(map.get(expectedQuery));
  }

  /**
   * Test for calling getAdaptiveFetch method with value true.
   */
  @Test
  public void testGettingAdaptiveFetchIfTrue()
      throws NoSuchFieldException, IllegalAccessException {
    boolean expectedResult = true;

    setAdaptiveFetchVariable(expectedResult);

    assertEquals(expectedResult, adaptiveFetchCache.getAdaptiveFetch());
  }

  /**
   * Test for calling getAdaptiveFetch method with value false.
   */
  @Test
  public void testGettingAdaptiveFetchIfFalse()
      throws NoSuchFieldException, IllegalAccessException {
    boolean expectedResult = false;

    setAdaptiveFetchVariable(expectedResult);

    assertEquals(expectedResult, adaptiveFetchCache.getAdaptiveFetch());
  }

  /**
   * Test for calling getFetchSizeForQuery method for not existing query. Should return value -1.
   */
  @Test
  public void testGettingFetchSizeForNotExistingQuery() {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = true;

    int resultSize = adaptiveFetchCache
        .getFetchSizeForQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(-1, resultSize);
  }

  /**
   * Test for calling getFetchSizeForQuery method for not existing query, but adaptiveFetch is set
   * to false. Should return value -1.
   */
  @Test
  public void testGettingFetchSizeForNotExistingQueryIfAdaptiveFetchFalse() {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = false;

    int resultSize = adaptiveFetchCache
        .getFetchSizeForQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(-1, resultSize);
  }

  /**
   * Test for calling getFetchSizeForQuery method for existing query. Should return set fetch size
   * for the query.
   */
  @Test
  public void testGettingFetchSizeForExistingQuery()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = true;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int expectedSize = 500;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(expectedSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    int resultSize = adaptiveFetchCache
        .getFetchSizeForQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(expectedSize, resultSize);
  }

  /**
   * Test for calling getFetchSizeForQuery method for existing query, but adaptiveFetch is set to
   * false. Should return value -1.
   */
  @Test
  public void testGettingFetchSizeForExistingQueryIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = false;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int newSize = 500;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(newSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    int resultSize = adaptiveFetchCache
        .getFetchSizeForQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(-1, resultSize);
  }

  /**
   * Test for calling removeQuery method for not existing query. Should nothing happen.
   */
  @Test
  public void testRemovingNotExistingQuery()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = true;

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(0, map.size());
  }

  /**
   * Test for calling removeQuery method for not existing query, but adaptiveFetch is set false.
   * Should nothing happen.
   */
  @Test
  public void testRemovingNotExistingQueryIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = false;

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    assertEquals(0, map.size());
  }

  /**
   * Test for calling removeQuery method for existing query. The query should be removed from the
   * map inside AdaptiveFetchCache.
   */
  @Test
  public void testRemovingExistingQuery()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = true;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setCounter(1);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    assertEquals(1, map.size());

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(0, map.size());
    assertNull(map.get(expectedQuery));
  }

  /**
   * Test for calling removeQuery method for existing query, but adaptiveFetch is set false. The
   * query shouldn't be removed.
   */
  @Test
  public void testRemovingExistingQueryIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = false;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setCounter(1);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    assertEquals(1, map.size());

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(1, map.size());
    assertNotNull(map.get(expectedQuery));
    assertEquals(1, map.get(expectedQuery).getCounter());
  }

  /**
   * Test for calling removeQuery method for existing query with counter set to 2. After call, query
   * shouldn't be removed, but counter set to 1. After next call, query should be removed.
   */
  @Test
  public void testRemovingExistingQueryWithLargeCounter()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = true;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setCounter(2);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(1, map.size());
    assertNotNull(map.get(expectedQuery));
    assertEquals(1, map.get(expectedQuery).getCounter());

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(0, map.size());
    assertNull(map.get(expectedQuery));
  }

  /**
   * Test for calling removeQuery method for existing query with counter set to 2, but with
   * adaptiveFetch set false. After both calls query should be removed and counter shouldn't
   * change.
   */
  @Test
  public void testRemovingExistingQueryWithLargeCounterIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query";
    boolean adaptiveFetch = false;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setCounter(2);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(1, map.size());
    assertNotNull(map.get(expectedQuery));
    assertEquals(2, map.get(expectedQuery).getCounter());

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    assertEquals(1, map.size());
    assertNotNull(map.get(expectedQuery));
    assertEquals(2, map.get(expectedQuery).getCounter());
  }

  /**
   * Test for calling removeQuery method for existing query with more queries put in the map. Only
   * query used in method call should be removed, other shouldn't change.
   */
  @Test
  public void testRemovingExistingQueryWithMoreQueriesCached()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    String expectedQuery3 = "test-query-3";
    boolean adaptiveFetch = true;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int expectedCounter1 = 1;
    int expectedCounter2 = 37;
    int expectedCounter3 = 14;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry1 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry1.setCounter(expectedCounter1);
    map.put(expectedQuery, adaptiveFetchCacheEntry1);

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry2 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry2.setCounter(expectedCounter2);
    map.put(expectedQuery2, adaptiveFetchCacheEntry2);

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry3 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry3.setCounter(expectedCounter3);
    map.put(expectedQuery3, adaptiveFetchCacheEntry3);

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    AdaptiveFetchCacheEntry resultInfo1 = map.get(expectedQuery);
    AdaptiveFetchCacheEntry resultInfo2 = map.get(expectedQuery2);
    AdaptiveFetchCacheEntry resultInfo3 = map.get(expectedQuery3);

    assertEquals(2, map.size());
    assertNull(resultInfo1);
    assertNotNull(resultInfo2);
    assertEquals(adaptiveFetchCacheEntry2, resultInfo2);
    assertEquals(expectedCounter2, resultInfo2.getCounter());
    assertNotNull(resultInfo3);
    assertEquals(adaptiveFetchCacheEntry3, resultInfo3);
    assertEquals(expectedCounter3, resultInfo3.getCounter());
  }

  /**
   * Test for calling removeQuery method for existing query with more queries put in the map, but
   * adaptiveFetch is set false. Queries shouldn't change
   */
  @Test
  public void testRemovingExistingQueryWithMoreQueriesCachedIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    String expectedQuery3 = "test-query-3";
    boolean adaptiveFetch = false;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int expectedCounter1 = 1;
    int expectedCounter2 = 37;
    int expectedCounter3 = 14;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry1 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry1.setCounter(expectedCounter1);
    map.put(expectedQuery, adaptiveFetchCacheEntry1);

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry2 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry2.setCounter(expectedCounter2);
    map.put(expectedQuery2, adaptiveFetchCacheEntry2);

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry3 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry3.setCounter(expectedCounter3);
    map.put(expectedQuery3, adaptiveFetchCacheEntry3);

    adaptiveFetchCache.removeQuery(adaptiveFetch, new MockUpQuery(expectedQuery));

    AdaptiveFetchCacheEntry resultInfo1 = map.get(expectedQuery);
    AdaptiveFetchCacheEntry resultInfo2 = map.get(expectedQuery2);
    AdaptiveFetchCacheEntry resultInfo3 = map.get(expectedQuery3);

    assertEquals(3, map.size());
    assertNotNull(resultInfo1);
    assertEquals(adaptiveFetchCacheEntry1, resultInfo1);
    assertEquals(expectedCounter1, resultInfo1.getCounter());
    assertNotNull(resultInfo2);
    assertEquals(adaptiveFetchCacheEntry2, resultInfo2);
    assertEquals(expectedCounter2, resultInfo2.getCounter());
    assertNotNull(resultInfo3);
    assertEquals(adaptiveFetchCacheEntry3, resultInfo3);
    assertEquals(expectedCounter3, resultInfo3.getCounter());
  }

  /**
   * Test for calling setAdaptiveFetch method with true value.
   */
  @Test
  public void testSettingAdaptiveFetchAsTrue()
      throws NoSuchFieldException, IllegalAccessException {
    boolean expectedAdaptiveFetch = true;

    adaptiveFetchCache.setAdaptiveFetch(expectedAdaptiveFetch);

    boolean resultAdaptiveFetch = getAdaptiveFetchVariable();

    assertEquals(expectedAdaptiveFetch, resultAdaptiveFetch);
  }

  /**
   * Test for calling setAdaptiveFetch method with false value.
   */
  @Test
  public void testSettingAdaptiveFetchAsFalse()
      throws NoSuchFieldException, IllegalAccessException {
    boolean expectedAdaptiveFetch = false;

    adaptiveFetchCache.setAdaptiveFetch(expectedAdaptiveFetch);

    boolean resultAdaptiveFetch = getAdaptiveFetchVariable();

    assertEquals(expectedAdaptiveFetch, resultAdaptiveFetch);
  }

  /**
   * Test for calling updateQueryFetchSize method. Method should update a value for a query.
   */
  @Test
  public void testUpdatingAdaptiveFetchSize()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    boolean adaptiveFetch = true;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int rowSize = 33;
    int startSize = size / rowSize - 15;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);

    assertNotNull(resultInfo);
    assertEquals(size / rowSize, resultInfo.getSize());
  }

  /**
   * Test for calling updateQueryFetchSize method, but adaptiveFetch is set false. Method shouldn't
   * update any values.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    boolean adaptiveFetch = false;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int rowSize = 33;
    int startSize = size / rowSize - 15;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);

    assertNotNull(resultInfo);
    assertEquals(startSize, resultInfo.getSize());
  }

  /**
   * Test for calling updateQueryFetchSize method for not existing query. Method shouldn't update
   * any values.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeForNotExistingQuery()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    boolean adaptiveFetch = true;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int rowSize = 33;
    int startSize = size / rowSize - 15;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery2, adaptiveFetchCacheEntry);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);
    AdaptiveFetchCacheEntry resultInfo2 = map.get(expectedQuery2);

    assertNull(resultInfo);
    assertNotNull(resultInfo2);
    assertEquals(adaptiveFetchCacheEntry, resultInfo2);
    assertEquals(startSize, resultInfo2.getSize());
    assertEquals(1, map.size());
  }

  /**
   * Test for calling updateQueryFetchSize method for not existing query, but adaptiveFetch is set
   * false. Method shouldn't update any values.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeForNotExistingQueryIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    boolean adaptiveFetch = false;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int rowSize = 33;
    int startSize = size / rowSize - 15;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery2, adaptiveFetchCacheEntry);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);
    AdaptiveFetchCacheEntry resultInfo2 = map.get(expectedQuery2);

    assertNull(resultInfo);
    assertNotNull(resultInfo2);
    assertEquals(adaptiveFetchCacheEntry, resultInfo2);
    assertEquals(startSize, resultInfo2.getSize());
    assertEquals(1, map.size());
  }

  /**
   * Test for calling updateQueryFetchSize method in a situation when there are more queries saved
   * in a map. The method should only change value for query used in a call.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeWithMoreQueriesInMap()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    boolean adaptiveFetch = true;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int rowSize = 33;
    int startSize = size / rowSize - 15;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry2 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry2.setSize(startSize);
    map.put(expectedQuery2, adaptiveFetchCacheEntry2);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);
    AdaptiveFetchCacheEntry resultInfo2 = map.get(expectedQuery2);

    assertNotNull(resultInfo);
    assertEquals(adaptiveFetchCacheEntry, resultInfo);
    assertEquals(size / rowSize, resultInfo.getSize());
    assertNotNull(resultInfo2);
    assertEquals(adaptiveFetchCacheEntry2, resultInfo2);
    assertEquals(startSize, resultInfo2.getSize());
    assertEquals(2, map.size());
  }

  /**
   * Test for calling updateQueryFetchSize method in a situation when there are more queries saved
   * in a map, but adaptiveFetch is set false. The method shouldn't change any values.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeWithMoreQueriesInMapIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    String expectedQuery2 = "test-query-2";
    boolean adaptiveFetch = false;

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    int rowSize = 33;
    int startSize = size / rowSize - 15;

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry2 = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry2.setSize(startSize);
    map.put(expectedQuery2, adaptiveFetchCacheEntry2);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);
    AdaptiveFetchCacheEntry resultInfo2 = map.get(expectedQuery2);

    assertNotNull(resultInfo);
    assertEquals(adaptiveFetchCacheEntry, resultInfo);
    assertEquals(startSize, resultInfo.getSize());
    assertNotNull(resultInfo2);
    assertEquals(adaptiveFetchCacheEntry2, resultInfo2);
    assertEquals(startSize, resultInfo2.getSize());
    assertEquals(2, map.size());
  }

  /**
   * Test for calling updateQueryFetchSize method with value to make computed value below minimum
   * value. The method should update a query to have value of minimum.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeWithMinimumSize()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    boolean adaptiveFetch = true;

    int rowSize = size + 1000;
    int startSize = 2;
    int expectedSize = 10;

    setMinimumSizeVariable(expectedSize);

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);

    assertNotNull(resultInfo);
    assertEquals(expectedSize, resultInfo.getSize());
  }

  /**
   * Test for calling updateQueryFetchSize method with value to make computed value below minimum
   * value, but adaptiveFetch is set false. The method shouldn't update size for a query.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeWithMinimumSizeIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    boolean adaptiveFetch = false;

    int rowSize = size + 1000;
    int startSize = 2;
    int expectedSize = 10;

    setMinimumSizeVariable(expectedSize);

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);

    assertNotNull(resultInfo);
    assertEquals(startSize, resultInfo.getSize());
  }

  /**
   * Test for calling updateQueryFetchSize method with value to make computed value above maximum
   * value. The method should update a query to have value of maximum.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeWithMaximumSize()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    boolean adaptiveFetch = true;

    int rowSize = 1;
    int startSize = 2;
    int expectedSize = size / rowSize - 20;

    setMaximumSizeVariable(expectedSize);

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchCacheEntry = new AdaptiveFetchCacheEntry();
    adaptiveFetchCacheEntry.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchCacheEntry);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);

    assertNotNull(resultInfo);
    assertEquals(expectedSize, resultInfo.getSize());
  }

  /**
   * Test for calling updateQueryFetchSize method with value to make computed value below maximum
   * value, but adaptiveFetch is set false. The method shouldn't update size for a query.
   */
  @Test
  public void testUpdatingAdaptiveFetchSizeWithMaximumSizeIfAdaptiveFetchFalse()
      throws NoSuchFieldException, IllegalAccessException {
    String expectedQuery = "test-query-1";
    boolean adaptiveFetch = false;

    int rowSize = 1;
    int startSize = 2;
    int expectedSize = size / rowSize - 20;

    setMaximumSizeVariable(expectedSize);

    Map<String, AdaptiveFetchCacheEntry> map = getInfoMapVariable();

    AdaptiveFetchCacheEntry adaptiveFetchQueryInfo = new AdaptiveFetchCacheEntry();
    adaptiveFetchQueryInfo.setSize(startSize);
    map.put(expectedQuery, adaptiveFetchQueryInfo);

    adaptiveFetchCache
      .updateQueryFetchSize(adaptiveFetch, new MockUpQuery(expectedQuery), rowSize);

    AdaptiveFetchCacheEntry resultInfo = map.get(expectedQuery);

    assertNotNull(resultInfo);
    assertEquals(startSize, resultInfo.getSize());
  }

  // Here are methods for retrieving values from adaptiveFetchCache without calling methods

  private Map<String, AdaptiveFetchCacheEntry> getInfoMapVariable()
      throws IllegalAccessException, NoSuchFieldException {
    Field field = adaptiveFetchCache.getClass().getDeclaredField(infoMapVariableName);
    field.setAccessible(true);
    return (Map<String, AdaptiveFetchCacheEntry>) field.get(adaptiveFetchCache);
  }

  private int getMinimumSizeVariable() throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass().getDeclaredField(minimumSizeVariableName);
    field.setAccessible(true);
    return (Integer) field.get(adaptiveFetchCache);
  }

  private int getMaximumSizeVariable() throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass().getDeclaredField(maximumSizeVariableName);
    field.setAccessible(true);
    return (Integer) field.get(adaptiveFetchCache);
  }

  private boolean getAdaptiveFetchVariable() throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass()
        .getDeclaredField(adaptiveFetchVariableName);
    field.setAccessible(true);
    return (Boolean) field.get(adaptiveFetchCache);
  }

  private long getMaximumBufferVariable() throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass()
        .getDeclaredField(maximumBufferSizeVariableName);
    field.setAccessible(true);
    return (Long) field.get(adaptiveFetchCache);
  }

  private void setMinimumSizeVariable(int value)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass().getDeclaredField(minimumSizeVariableName);
    field.setAccessible(true);
    field.set(adaptiveFetchCache, value);
  }

  private void setMaximumSizeVariable(int value)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass()
        .getDeclaredField(maximumSizeVariableName);
    field.setAccessible(true);
    field.set(adaptiveFetchCache, value);
  }

  private void setAdaptiveFetchVariable(boolean value)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass()
        .getDeclaredField(adaptiveFetchVariableName);
    field.setAccessible(true);
    field.set(adaptiveFetchCache, value);
  }

  private void setMaximumBufferVariable(long value)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = adaptiveFetchCache.getClass()
        .getDeclaredField(maximumBufferSizeVariableName);
    field.setAccessible(true);
    field.set(adaptiveFetchCache, value);
  }

  /**
   * Class to mock object with Query interface. As AdaptiveFetchCache is using only
   * getNativeSql method from Query interface, other shouldn't be called.
   */
  private class MockUpQuery implements Query {

    public String sql;

    MockUpQuery(String sql) {
      this.sql = sql;
    }

    @Override
    public ParameterList createParameterList() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public String toString(ParameterList parameters) {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public String getNativeSql() {
      return this.sql;
    }

    @Override
    public SqlCommand getSqlCommand() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public void close() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public boolean isStatementDescribed() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public boolean isEmpty() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public int getBatchSize() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public Map<String, Integer> getResultSetColumnNameIndexMap() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }

    @Override
    public Query[] getSubqueries() {
      throw new WrongMethodCallException("Method shouldn't be called.");
    }
  }

  /**
   * An exception used when method shouldn't be called in MockUpQuery class.
   */
  private class WrongMethodCallException extends RuntimeException {

    WrongMethodCallException(String msg) {
      super(msg);
    }

  }

}
