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

import org.postgresql.protocol.TypeRef;

import static org.postgresql.types.Type.CATALOG_NAMESPACE;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Storage and loading for all the known types of a given connection.
 *
 * @author kdubb
 *
 */
public class Registry {

  public interface TypeLoader {
    Type load(int oid) throws IOException;
    CompositeType loadRelation(int relationOid) throws IOException;
    Type load(QualifiedName name) throws IOException;
    Type load(String name) throws IOException;

  }

  private SharedRegistry sharedRegistry;
  private Map<String, Type> commonTypes;
  private TypeLoader loader;

  public Registry(SharedRegistry sharedRegistry, TypeLoader loader) {
    this.sharedRegistry = sharedRegistry;
    this.commonTypes = new HashMap<>();
    this.loader = loader;
  }

  public SharedRegistry getShared() {
    return sharedRegistry;
  }

  /**
   * Resolves a type reference to an actual type object.
   *
   * @param typeRef Type reference to resolve
   * @return Resolved type object
   */
  public Type resolve(TypeRef typeRef) throws IOException {

    if (typeRef instanceof Type) {
      return (Type) typeRef;
    }

    return loadType(typeRef.getOid());
  }

  /**
   * Loads a type by its type-id (aka OID)
   *
   * @param typeId The type's id
   * @return Type object or null, if none found
   */
  public Type loadType(int typeId) throws IOException {

    if (typeId == 0)
      return null;

    return sharedRegistry.findOrLoadType(typeId, loader);
  }

  /**
   * Loads a base type from the postgres schema catalog
   *
   *
   * @param localName Name of the type in the <code>pg_catalog</code> namespace.
   * @throws IllegalArgumentException When a type cannot be found.
   * @return Type object
   */
  public Type loadBaseType(String localName) {

    QualifiedName name = new QualifiedName(CATALOG_NAMESPACE, localName);

    Type type;
    try {
      type = sharedRegistry.findOrLoadType(name, loader);
    }
    catch (IOException e) {
      throw new RuntimeException("Unable to load base type: " + localName);
    }

    if (type == null) {
      throw new IllegalArgumentException("Unknown type");
    }

    return type;
  }

  /**
   * Loads a relation (aka table) type by its relation-id
   *
   * @param relationId Relation ID of the type to load
   * @return Relation type or null
   */
  public CompositeType loadRelationType(int relationId) throws IOException {

    if (relationId == 0)
      return null;

    return sharedRegistry.findOrLoadRelationType(relationId, loader);
  }

  /**
   * Loads a type by name.
   *
   * As it is resolved by the server, the name can be a qualified, unqualified or
   * alias name; anything acceptable to the server.
   *
   * Stable types are those that are expected to be available and not to change;
   * an example being "hstore".
   *
   * This method caches the type in the non-shared registry. Any changes
   * to the type (e.g. dropping and re-creating the "hstore" extension) will
   * cause the cache to become stale.
   *
   * @param typeName Name of the type (can be anything accepted by the server)
   * @return Type object or null, if none found
   */
  public Type loadStableType(String typeName) throws IOException {

    Type type;
    if ((type = commonTypes.get(typeName)) == null) {

      type = loadTransientType(typeName);

      commonTypes.put(typeName, type);
    }

    return type;
  }

  /**
   * Loads a type by name.
   *
   * As it is resolved by the server, the name can be a qualified, unqualified or
   * alias name; anything acceptable to the server.
   *
   * @param typeName Name of the type (can be anything accepted by the server)
   * @return Type object or null, if none found
   */
  public Type loadTransientType(String typeName) throws IOException {

    return loader.load(typeName);
  }

}
