/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core.v3;

import static org.postgresql.core.Oid.UNSPECIFIED;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Utils;

import java.util.Arrays;


/**
 * Purpose of this object is to support batched query re write behaviour. Responsibility for
 * tracking the batch size and implement the clean up of the query fragments after the batch execute
 * is complete. Intended to be used to wrap a Query that is present in the batchStatements
 * collection.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 * @author Christopher Deckers (chrriis@gmail.com)
 *
 */
public class BatchedQueryDecorator extends SimpleQuery {

  private final int[] originalPreparedTypes;
  private int valuesBraceOpenPosition;
  private int valuesBraceClosePosition;
  private boolean isPreparedTypesSet;
  private Integer isParsed = 0;

  private byte[] batchedEncodedName;

  public BatchedQueryDecorator(NativeQuery query, int valuesBraceOpenPosition,
      int valuesBraceClosePosition,
      ProtocolConnectionImpl protoConnection) {
    super(query, protoConnection);
    int paramCount = getBindPositions();
    this.valuesBraceOpenPosition = valuesBraceOpenPosition;
    this.valuesBraceClosePosition = valuesBraceClosePosition;
    originalPreparedTypes = new int[paramCount];
    if (getStatementTypes() != null && getStatementTypes().length > 0) {
      System.arraycopy(getStatementTypes(), 0, originalPreparedTypes, 0,
          paramCount);
    } else {
      Arrays.fill(originalPreparedTypes, UNSPECIFIED);
    }

  }

  private BatchedQueryDecorator(NativeQuery query, int valuesBraceOpenPosition,
      int valuesBraceClosePosition,
      ProtocolConnectionImpl protoConnection, int[] originalPreparedTypes) {
    super(query, protoConnection);
    this.valuesBraceOpenPosition = valuesBraceOpenPosition;
    this.valuesBraceClosePosition = valuesBraceClosePosition;
    this.originalPreparedTypes = originalPreparedTypes;
    setStatementName(null);
  }


  public BatchedQueryDecorator deriveForNewBatches() {
    return new BatchedQueryDecorator(getNativeQuery(), valuesBraceOpenPosition,
        valuesBraceClosePosition, getProtoConnection(), originalPreparedTypes);
  }

  /**
   * Reset the batched query for next use.
   */
  public void reset() {
    super.setStatementTypes(originalPreparedTypes);
    resetBatchedCount();
    length = 0;
  }

  @Override
  public boolean isStatementReWritableInsert() {
    return true;
  }

  /**
   * The original meta data may need updating.
   */
  @Override
  public void setStatementTypes(int[] types) {
    super.setStatementTypes(types);
    if (isOriginalStale(types)) {
      updateOriginal(types);
    }
  }

  /**
   * Get the statement types for all parameters in the batch.
   *
   * @return int an array of {@link org.postgresql.core.Oid} parameter types
   */
  @Override
  public int[] getStatementTypes() {
    int types[] = super.getStatementTypes();
    if (isOriginalStale(types)) {
      /*
       * Use opportunity to update originals if a ParameterDescribe has been
       * called.
       */
      updateOriginal(types);
    }
    return resizeTypes();
  }

  /**
   * Check fields and update if out of sync
   *
   * @return int an array of {@link org.postgresql.core.Oid} parameter types
   */
  private int[] resizeTypes() {
    // provide types depending on batch size, which may vary
    int expected = getBindPositions();
    int[] types = super.getStatementTypes();
    int before = 0;

    if (types == null) {
      types = fill(expected);
    }
    if (types.length < expected) {
      before = types.length;
      types = fill(expected);
    }
    if (before != types.length) {
      setStatementTypes(types);
    }
    return types;
  }

  private int[] fill(int expected) {
    int[] types = Arrays.copyOf(originalPreparedTypes, expected);
    for (int row = 1; row < getBatchSize(); row++) {
      System.arraycopy(originalPreparedTypes, 0, types, row
          * originalPreparedTypes.length, originalPreparedTypes.length);
    }
    return types;
  }

  @Override
  boolean isPreparedFor(int[] paramTypes) {
    resizeTypes();
    return isStatementParsed() && super.isPreparedFor(paramTypes);
  }

  @Override
  byte[] getEncodedStatementName() {
    if (batchedEncodedName == null) {
      String n = super.getStatementName();
      if (n != null) {
        batchedEncodedName = Utils.encodeUTF8(n);
      }
    }
    return batchedEncodedName;
  }

  @Override
  void setStatementName(String statementName) {
    if (statementName == null) {
      batchedEncodedName = null;
      super.setStatementName(null);
    } else {
      super.setStatementName(statementName);
      batchedEncodedName = Utils.encodeUTF8(statementName);
    }
  }

  /**
   * Detect when the vanilla prepared type meta data is out of date.
   * Initially some types will be Oid.UNSPECIFIED. The BE will update this to the
   * current type for the column for future reference by the FE.
   *
   * @param preparedTypes
   *          meta data to compare with
   * @return boolean value indicating if internal type information needs
   *         updating
   */
  private boolean isOriginalStale(int[] preparedTypes) {
    if (isPreparedTypesSet) {
      return false;
    }
    if (preparedTypes == null) {
      return false;
    }
    if (preparedTypes.length == 0) {
      return false;
    }
    if (preparedTypes.length < originalPreparedTypes.length) {
      return false;
    }
    int maxPos = originalPreparedTypes.length - 1;
    for (int pos = 0; pos <= maxPos; pos++) {
      if (originalPreparedTypes[pos] == UNSPECIFIED
          && preparedTypes[pos] != UNSPECIFIED) {
        return true;
      }
    }
    return false;
  }

  /**
   * Update field type meta data after the FE issues a ParameterDescribe to
   * the BE. Compare current types with the types passed in.
   * @param preparedTypes int[] the most recent preparedTypes information
   */
  private void updateOriginal(int[] preparedTypes) {
    if (preparedTypes == null) {
      return;
    }
    if (preparedTypes.length == 0) {
      return;
    }
    isPreparedTypesSet = true;
    int maxPos = originalPreparedTypes.length - 1;
    for (int pos = 0; pos <= maxPos; pos++) {
      if (preparedTypes[pos] != UNSPECIFIED) {
        if (originalPreparedTypes[pos] == UNSPECIFIED) {
          originalPreparedTypes[pos] = preparedTypes[pos];
        }
      } else {
        isPreparedTypesSet = false;
      }
    }
  }

  private boolean isStatementParsed() {
    return isParsed.equals(getBindPositions());
  }

  /**
   * Method to receive notification of the parsed/prepared status of the
   * statement.
   *
   * @param prepared
   *          state
   */
  public void registerQueryParsedStatus(boolean prepared) {
    isParsed = getBindPositions();
  }

  public int computeMaxBatchSize() {
    return Math.min((2000 - 1) / originalPreparedTypes.length, 100);
  }

  /**
   * Method to return the sql based on number of batches. Skipping the initial
   * batch.
   */
  @Override
  String getNativeSql() {
    // dynamically build sql with parameters for batches
    String nativeSql = super.getNativeSql();
    if (nativeSql == null) {
      return "";
    }
    int c = super.getNativeQuery().bindPositions.length;
    int bs = getBatchSize();
    if (bs < 2) {
      return nativeSql;
    }
    int[] bindPositions = getNativeQuery().bindPositions;
    int valuesBlockCharCount = 1; // Comma
    // Split the values section around every dynamic parameter.
    String[] chunks = new String[1 + bindPositions.length];
    chunks[0] = nativeSql.substring(valuesBraceOpenPosition, bindPositions[0]);
    valuesBlockCharCount += chunks[0].length();
    for (int i = 0; i < bindPositions.length; i++) {
      int startIndex = bindPositions[i] + 2;
      int endIndex =
          i < bindPositions.length - 1 ? bindPositions[i + 1] : valuesBraceClosePosition + 1;
      for (; startIndex < endIndex; startIndex++) {
        if (!Character.isDigit(nativeSql.charAt(startIndex))) {
          break;
        }
      }
      chunks[i + 1] = nativeSql.substring(startIndex, endIndex);
      valuesBlockCharCount += chunks[i + 1].length();
    }
    calculateLength(nativeSql.length(), c, bs - 1, valuesBlockCharCount);
    StringBuilder s = new StringBuilder(length);
    // Add query until end of values parameter block.
    s.append(nativeSql.substring(0, valuesBraceClosePosition + 1));
    for (int i = 2; i <= bs; i++) {
      int pos = ((i - 1) * c) + 1;
      s.append(",");
      s.append(chunks[0]);
      for (int j = 1; j < chunks.length; j++) {
        s.append("$");
        s.append(pos++);
        s.append(chunks[j]);
      }
    }
    // Add trailing content: final query is like original with multi values.
    // This could contain "--" comments, so it is important to add them at end.
    s.append(nativeSql.substring(valuesBraceClosePosition + 1));
    return s.toString();
  }

  @Override
  public String toString() {
    return getNativeSql();
  }

  /**
   * Calculate the text length necessary for the statement. Including brackets,
   * dollars, commas, numbers plus the initial sql text.
   * Do this to avoid repeated calls to
   * AbstractStringBuilder.expandCapacity(...) and Arrays.copyOf
   *
   * @param init int Length of sql supplied by user
   * @param p int Number of parameters in a batch
   * @param remaining int Remaining batches to process
   * @return int Size of generated sql
   */
  int calculateLength(int init, int p, int remaining, int valuesBlockCharCount) {
    int count = (p * remaining); // remaining parameters
    length = init + remaining * (valuesBlockCharCount + p); // initial, empty blocks, dollar
    remainingParams = count;
    calculate(999999999, 10, p);
    calculate(99999999, 9, p);
    calculate(9999999, 8, p);
    calculate(999999, 7, p);
    calculate(99999, 6, p);
    calculate(9999, 5, p);
    calculate(999, 4, p);
    calculate(99, 3, p);
    calculate(9, 2, p);
    calculate(0, 1, p);
    return length;
  }

  /**
   * Calculate the length of text necessary for every number in the sql.
   * @param boundary int the upper bound value for digit length
   * @param numberLength int length of the digits being calculated
   * @param p int parameters in a batch
   */
  private void calculate(int boundary, int numberLength, int p) {
    if ((remainingParams + p) > boundary) {
      int nextRangeParamCount = 0;
      if (p < (boundary + 1)) {
        nextRangeParamCount = boundary - p;
      }
      int params = remainingParams - nextRangeParamCount;
      length += params * numberLength;
      remainingParams -= params;
    }
  }

  private int remainingParams = 0;
  private int length = 0;
}
