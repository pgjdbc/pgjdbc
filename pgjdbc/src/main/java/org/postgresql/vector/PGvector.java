/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
package org.postgresql.vector;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.Arrays;

public class PGvector extends PGobject implements PGBinaryObject, Serializable, Cloneable{

  private static final int HEADER_SIZE = 4;
  private float[] @Nullable vector;
  public PGvector() {
    this.type = "vector";
    vector = null;
  }

  public PGvector(float[] in) {
    this();
    vector = in;
  }

  public PGvector(String in) throws SQLException {
    this();
    setValue(in);
  }

  @Override
  public void setByteValue(byte[] value, int offset) throws SQLException {
    final int floatArraySize = ByteConverter.int2(value,0);

    if (vector == null ) {
      vector = new float[floatArraySize];
    } else if ( offset == 0 ) {
      if (vector.length < floatArraySize ) {
        // extend the vector array
        vector = Arrays.copyOf(vector, floatArraySize);
      }
    } else {
      if (offset + floatArraySize > vector.length) {
        // extend the array
        vector = Arrays.copyOf(vector, offset + floatArraySize);
      }
    }
    // copy the incoming data into the array
    for (int i=0; i< floatArraySize; i++){
      vector[offset++] = ByteConverter.float4(value, i*4 + 4);
    }
  }

  @Override
  public void setValue(@Nullable String value) throws SQLException {
    if (value == null) {
      vector=null;
    } else {
      String[] sp = value.substring(1, value.length() - 1).split(",");
      vector = new float[sp.length];
      for (int i = 0; i < sp.length; i++) {
        vector[i] = Float.parseFloat(sp[i]);
      }
    }
  }

  @Override
  public @Nullable String getValue() {
    if (vector == null) {
      return null;
    } else {
      return Arrays.toString(vector).replace(" ", "");
    }
  }

  @Override
  public boolean isNull() {
    return vector == null;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    return super.equals(obj);
  }



  @Override
  public String toString() {
    return super.toString();
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public int lengthInBytes() {
    if (isNull()) {
      return 0;
    } else {
      return vector.length * 4 + HEADER_SIZE;
    }
  }

  @Override
  public void toBytes(byte[] bytes, int offset) {
    if (isNull()) {
      return;
    }
    int pos=0;

    // set the number of dimensions
    ByteConverter.int2(bytes,pos,vector.length);
    // set the flags unused
    pos=2;
    ByteConverter.int2(bytes, pos, 0);
    // set the oid
    pos=4;
    for (int index = 0 ; index < vector.length ; index++ ) {
      ByteConverter.float4(bytes,pos, vector[index] );
      pos += 4;
    }
  }
}
