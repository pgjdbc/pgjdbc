/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.types;

/**
 * Represents a single Struct in the databases.
 */
public class Type {
  // Should the user be able to change the variables?
  // Should the user be able to add to the attributes?

  public static final String CATALOG_NAMESPACE = "pg_catalog";
  public static final String PUBLIC_NAMESPACE = "public";

  public enum Category {
    Array('A'),
    Boolean('B'),
    Composite('C'),
    DateTime('D'),
    Enumeration('E'),
    Geometry('G'),
    NetworkAddress('I'),
    Numeric('N'),
    Psuedo('P'),
    Range('R'),
    String('S'),
    Timespan('T'),
    User('U'),
    BitString('V'),
    Unknown('X');

    private char id;

    Category(char id) {
      this.id = id;
    }

    public char getId() {
      return id;
    }
  }

  private int id;
  private QualifiedName name;
  private Short length;
  private Category category;

  // Empty constructor
  public Type(){}

  public int getOid() {
    return id;
  }

  public int getId() {
    return id;
  }

  public String getName() {
    return name.getLocalName();
  }

  public Short getLength() {
    return length;
  }

  public Category getCategory() {
    return category;
  }

  /**
   * Strips all "wrapping" type (e.g. arrays, domains) and returns
   * the base type
   *
   * @return Base type after all unwrapping
   */
  public Type unwrap() {
    return this;
  }

  @Override
  public String toString() {
    return name.toString() + '(' + id + ')';
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + id;
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Type other = (Type) obj;
    return id == other.id;
  }
}
