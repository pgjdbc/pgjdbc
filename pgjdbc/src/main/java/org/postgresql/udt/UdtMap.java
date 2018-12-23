/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsulates a type map along with inverted maps.
 * <p>
 * No defensive copy is made of the type map, it is assumed to not change for
 * the life of this object.
 * </p>
 * <p>
 * The inverted maps are only created when first accessed.
 * </p>
 */
public class UdtMap {

  private static final Logger LOGGER = Logger.getLogger(UdtMap.class.getName());

  public static final UdtMap EMPTY = new UdtMap(Collections.<String, Class<?>>emptyMap());

  /**
   * The current type mappings.
   */
  private final Map<String, Class<?>> typemap;

  /**
   * The set of types directly mapped to each class - used for type inference.
   */
  private final Map<Class<?>, Set<String>> invertedDirect = new HashMap<Class<?>, Set<String>>();

  /**
   * The set of all known types mapped to each class - used for type inference.
   */
  private final Map<Class<?>, Set<String>> invertedInherited = new HashMap<Class<?>, Set<String>>();

  public UdtMap(Map<String, Class<?>> typemap) {
    this.typemap = typemap;
  }

  /**
   * @return the type map
   *
   * @see BaseConnection#getTypeMap()
   */
  public Map<String, Class<?>> getTypeMap() {
    return typemap;
  }

  /**
   * @see #getInvertedDirect(java.lang.Class)
   * @see #getInvertedInherited(java.lang.Class)
   */
  // TODO: Maybe we need a way to call this only for Connection maps, since other inverted maps might be even shorter lived
  //       Or just do not make the sub-sets unmodifiable at all, perhaps making the first set as singleton, then switch to HashSet on second element
  private static void makeValuesUnmodifiable(Map<Class<?>, Set<String>> invertedMap) {
    for (Map.Entry<Class<?>, Set<String>> entry : invertedMap.entrySet()) {
      Set<String> types = entry.getValue();
      if (types.size() == 1) {
        entry.setValue(Collections.singleton(types.iterator().next()));
      } else {
        entry.setValue(Collections.unmodifiableSet(types));
      }
    }
  }

  /**
   * Adds a new element to an inverted map.
   *
   * @param invertedMap the map to add to
   * @param clazz the class to add
   * @param type the type that maps to this class
   *
   * @return  {@code true} when the class is added to the map, {@code false} when already existed.
   *
   * @see  #getInvertedDirect(java.lang.Class)
   */
  private static boolean addInverted(Map<Class<?>, Set<String>> invertedMap, Class<?> clazz, String type) {
    Set<String> types = invertedMap.get(clazz);
    if (types == null) {
      types = new HashSet<String>();
      invertedMap.put(clazz, types);
    }
    boolean added = types.add(type);
    if (added) {
      if (LOGGER.isLoggable(Level.FINER)) {
        LOGGER.log(Level.FINER, "Added: {0} -> {1}", new Object[] {clazz.getName(), type});
      }
    } else {
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.log(Level.FINEST, "Not added: {0} -> {1}", new Object[] {clazz.getName(), type});
      }
    }
    return added;
  }

  /**
   * Gets the set of types that directly map to the given class.
   * This performs the inverse operation of {@link #getTypeMap()}.
   * <p>
   * Supports types that are mapped directly to {@link Object}.
   * </p>
   *
   * @param clazz the class that a mapped type must be
   *
   * @return the unmodifiable set of all types that map to the given class or
   *         an empty set when the given class is not in the typemap.
   */
  public Set<String> getInvertedDirect(Class<?> clazz) {
    if (typemap.isEmpty()) {
      return Collections.<String>emptySet();
    }
    Set<String> types;
    synchronized (invertedDirect) {
      if (invertedDirect.isEmpty()) {
        for (Map.Entry<String, Class<?>> entry : typemap.entrySet()) {
          addInverted(invertedDirect, entry.getValue(), entry.getKey());
        }
        // Make each type set unmodifiable
        makeValuesUnmodifiable(invertedDirect);
        LOGGER.log(Level.FINE, "  invertedDirect = {0}", invertedDirect);
      }
      types = invertedDirect.get(clazz);
    }
    return (types != null) ? types : Collections.<String>emptySet();
  }

  /**
   * Recursively adds the class, all super classes, all interfaces,
   * and all extended interfaces - does not add {@link Object}.
   *
   * @param invertedMap the map to add to
   * @param current the class to add
   * @param type the type that maps to this class
   *
   * @see  #addInverted(java.util.Map, java.lang.Class, java.lang.String)
   * @see  #getInvertedInherited(java.lang.Class)
   */
  private static void addAllInverted(Map<Class<?>, Set<String>> invertedMap, Class<?> current, String type) {
    while (current != Object.class && current != null) {
      if (addInverted(invertedMap, current, type)) {
        for (Class<?> iface : current.getInterfaces()) {
          addAllInverted(invertedMap, iface, type);
        }
        current = current.getSuperclass();
      } else {
        // This class has already been mapped
        if (LOGGER.isLoggable(Level.FINER)) {
          LOGGER.log(Level.FINER, "Already mapped, break: {0} -> {1}", new Object[] {current.getName(), type});
        }
        break;
      }
    }
  }

  /**
   * Gets the set of all known types types that map to the given class.
   * This performs the inverse operation of {@link #getTypeMap()}, but matching
   * all classes and interfaces for each mapped type.
   * <p>
   * Does not include {@link Object} in the inverted map.  As type inference
   * to simply {@link Object} is of limited use, there is no benefit to mapping
   * all registered custom types in a big set under {@link Object}.
   * </p>
   *
   * @param clazz the class that a mapped type may be, extend, or implement
   *
   * @return the unmodifiable set of all types that map to the given class or
   *         an empty set when the given class is not in the typemap.
   */
  public Set<String> getInvertedInherited(Class<?> clazz) {
    if (typemap.isEmpty()) {
      return Collections.<String>emptySet();
    }
    Set<String> types;
    synchronized (invertedInherited) {
      if (invertedInherited.isEmpty()) {
        for (Map.Entry<String, Class<?>> entry : typemap.entrySet()) {
          addAllInverted(invertedInherited, entry.getValue(), entry.getKey());
        }
        // Make each type set unmodifiable
        makeValuesUnmodifiable(invertedInherited);
        LOGGER.log(Level.FINE, "  invertedInherited = {0}", invertedInherited);
      }
      types = invertedInherited.get(clazz);
    }
    return (types != null) ? types : Collections.<String>emptySet();
  }
}
