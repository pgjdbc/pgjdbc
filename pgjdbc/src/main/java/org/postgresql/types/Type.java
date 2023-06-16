/**
 * Copyright (c) 2013, impossibl.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of impossibl.com nor the names of its contributors may
 *    be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
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
