/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.jdbc.TypeInfoCache.types;

import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

class TypeInfoCacheStorage {
  static final TypeInfoCacheStorage GLOBAL_INSTANCE = new TypeInfoCacheStorage();

  // pgname (String) -> java.sql.Types (Integer)
  final Map<String, Integer> pgNameToSQLType;

  final Map<Integer, Integer> oidToSQLType;

  // pgname (String) -> java class name (String)
  // ie "text" -> "java.lang.String"
  final Map<String, String> pgNameToJavaClass;

  // oid (Integer) -> pgname (String)
  final Map<Integer, String> oidToPgName;
  // pgname (String) -> oid (Integer)
  final Map<String, Integer> pgNameToOid;

  final Map<String, Integer> javaArrayTypeToOid;

  // pgname (String) -> extension pgobject (Class)
  final Map<String, Class<? extends PGobject>> pgNameToPgObject;

  // type array oid -> base type's oid
  final Map<Integer, Integer> pgArrayToPgType;

  // array type oid -> base type array element delimiter
  final Map<Integer, Character> arrayOidToDelimiter;

  private boolean allTypesCached;

  final ResourceLock lock = new ResourceLock();

  TypeInfoCacheStorage() {
    oidToPgName = new HashMap<>((int) Math.round(types.length * 1.5));
    pgNameToOid = new HashMap<>((int) Math.round(types.length * 1.5));
    javaArrayTypeToOid = new HashMap<>((int) Math.round(types.length * 1.5));
    pgNameToJavaClass = new HashMap<>((int) Math.round(types.length * 1.5));
    pgNameToPgObject = new HashMap<>((int) Math.round(types.length * 1.5));
    pgArrayToPgType = new HashMap<>((int) Math.round(types.length * 1.5));
    arrayOidToDelimiter = new HashMap<>((int) Math.round(types.length * 2.5));

    // needs to be synchronized because the iterator is returned
    // from getPGTypeNamesWithSQLTypes()
    pgNameToSQLType = Collections.synchronizedMap(new HashMap<String, Integer>((int) Math.round(types.length * 1.5)));
    oidToSQLType = Collections.synchronizedMap(new HashMap<Integer, Integer>((int) Math.round(types.length * 1.5)));

    for (Object[] type : types) {
      String pgTypeName = (String) type[0];
      Integer oid = (Integer) type[1];
      Integer sqlType = (Integer) type[2];
      String javaClass = (String) type[3];
      Integer arrayOid = (Integer) type[4];

      addCoreType(pgTypeName, oid, sqlType, javaClass, arrayOid);
    }

    pgNameToJavaClass.put("hstore", Map.class.getName());
  }

  void addCoreType(String pgTypeName, Integer oid, Integer sqlType,
      String javaClass, Integer arrayOid) {
    try (ResourceLock ignore = lock.obtain()) {
      pgNameToJavaClass.put(pgTypeName, javaClass);
      pgNameToOid.put(pgTypeName, oid);
      oidToPgName.put(oid, pgTypeName);
      javaArrayTypeToOid.put(javaClass, arrayOid);
      pgArrayToPgType.put(arrayOid, oid);
      pgNameToSQLType.put(pgTypeName, sqlType);
      oidToSQLType.put(oid, sqlType);

      // Currently we hardcode all core types array delimiter
      // to a comma. In a stock install the only exception is
      // the box datatype and it's not a JDBC core type.
      //
      char delim = ',';
      if ("box".equals(pgTypeName)) {
        delim = ';';
      }
      arrayOidToDelimiter.put(oid, delim);
      arrayOidToDelimiter.put(arrayOid, delim);

      String pgArrayTypeName = pgTypeName + "[]";
      pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
      pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
      oidToSQLType.put(arrayOid, Types.ARRAY);
      pgNameToOid.put(pgArrayTypeName, arrayOid);
      pgArrayTypeName = "_" + pgTypeName;
      if (!pgNameToJavaClass.containsKey(pgArrayTypeName)) {
        pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
        pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
        pgNameToOid.put(pgArrayTypeName, arrayOid);
        oidToPgName.put(arrayOid, pgArrayTypeName);
      }
    }
  }

  interface CacheSQLTypesHandler {
    void run() throws SQLException;
  }

  void checkCacheSQLTypes(CacheSQLTypesHandler cacheSQLTypesHandler) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!allTypesCached) {
        cacheSQLTypesHandler.run();
        allTypesCached = true;
      }
    }
  }
}
