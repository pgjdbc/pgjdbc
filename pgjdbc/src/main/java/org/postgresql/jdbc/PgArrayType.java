package org.postgresql.jdbc;

import org.postgresql.core.Oid;

/**
 * Represents a PostgreSQL array type.
 */
public class PgArrayType extends PgType {
  private final PgType elementType;

  /**
   * Constructs a new PgArrayType.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param elementType the element type
   */
  public PgArrayType(ObjectName typeName, String fullName, int oid, PgType elementType) {
    super(typeName, fullName, oid, 'b', 'A', -1, elementType.getOid(), Oid.UNSPECIFIED, Oid.UNSPECIFIED);
    this.elementType = elementType;
  }

  /**
   * Gets the element type.
   *
   * @return the element type
   */
  public PgType getElementType() {
    return elementType;
  }

  /**
   * Creates a new PgArrayType from a base type.
   *
   * @param baseType the base type
   * @param arrayOid the OID of the array type
   * @return a new PgArrayType
   */
  public static PgArrayType fromBaseType(PgType baseType, int arrayOid) {
    ObjectName arrayTypeName = new ObjectName(baseType.getTypeName().getNamespace(), "_" + baseType.getTypeName().getName());
    String arrayFullName = baseType.getFullName() + "[]";
    return new PgArrayType(arrayTypeName, arrayFullName, arrayOid, baseType);
  }
}
