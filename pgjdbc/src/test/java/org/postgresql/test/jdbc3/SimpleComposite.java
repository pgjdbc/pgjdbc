/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public class SimpleComposite extends PGobject {

  int i = 0;
  float d;
  UUID u;

  public SimpleComposite() {
    setType("Composite.simplecompositetest");
  }

  public int getI() {
    return i;
  }

  public void setI(int i) {
    this.i = i;
  }

  public float getD() {
    return d;
  }

  public void setD(float d) {
    this.d = d;
  }

  public UUID getU() {
    return u;
  }

  public void setU(UUID u) {
    this.u = u;
  }

  /**
   * This method sets the value of this object. It must be overridden.
   *
   * @param value a string representation of the value of the object
   * @throws SQLException thrown if value is invalid for this type
   */
  @Override
  public void setValue(@Nullable String value) throws SQLException {
    super.setValue(value);
    String [] parts = value.substring(1,value.length() - 1).split(",");
    i = Integer.parseInt(parts[0]);
    d = Float.parseFloat(parts[1]);
    u = UUID.fromString(parts[2]);
  }

  /**
   * This must be overridden, to return the value of the object, in the form required by
   * org.postgresql.
   *
   * @return the value of this object
   */
  @Override
  public @Nullable String getValue() {
    return "(" + i + "," + d + "," + u + ")";
  }

  /**
   * Returns true if the current object wraps `null` value.
   * This might be helpful
   *
   * @return true if the current object wraps `null` value.
   */
  @Override
  public boolean isNull() {
    return super.isNull();
  }

  /**
   * This must be overridden to allow comparisons of objects.
   *
   * @param obj Object to compare with
   * @return true if the two boxes are identical
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    return super.equals(obj);
  }

  /**
   * This must be overridden to allow the object to be cloned.
   */
  @Override
  public Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  /**
   * This is defined here, so user code need not override it.
   *
   * @return the value of this object, in the syntax expected by org.postgresql
   */
  @Override
  public String toString() {
    return super.toString();
  }

  /**
   * Compute hash. As equals() use only value. Return the same hash for the same value.
   *
   * @return Value hashcode, 0 if value is null {@link Objects#hashCode(Object)}
   */
  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
