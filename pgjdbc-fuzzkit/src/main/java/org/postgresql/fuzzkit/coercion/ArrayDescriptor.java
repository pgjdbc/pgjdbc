/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An array {@link PgTypeDescriptor}: a container backend type over a scalar {@link #element()}. One
 * descriptor covers every dimension of that element -- {@code int4[]}, {@code int4[][]},
 * {@code int4[][][]} all share the {@code _int4} OID, since the number of dimensions lives in the
 * value and the target class, not the type ({@code TypeInfoCache} keeps a single {@code _int4} entry).
 *
 * <p>An array carries two axes of variation the fuzzers walk, both producing a distinct target class
 * for the same backend type:
 *
 * <ul>
 *   <li>{@link #dims()} -- the number of dimensions, {@code {1, 2, 3}}. The codec already decodes the
 *       nested shape end to end, so the descriptor only names the axis.</li>
 *   <li>{@link #leafReprs()} -- the leaf representation, {@link LeafRepr#BOXED} always and
 *       {@link LeafRepr#PRIMITIVE} when the element's natural class has a primitive (an {@code int4}
 *       leaf is {@code Integer} or {@code int}; a {@code text} leaf is {@code String} only).</li>
 * </ul>
 *
 * <p>The target class is the derivation of the two axes: {@code targetClass(BOXED, 2)} is
 * {@code Integer[][]} and {@code targetClass(PRIMITIVE, 2)} is {@code int[][]}. An array's fidelity is
 * {@link Fidelity#DEEP_EQUALS}, which unwraps every dimension and both leaf representations with one
 * comparison.
 *
 * <p>Arrays are not populated in the coercion dictionaries ({@link ReadCoercions} /
 * {@link WriteCoercions}), so the coercion guards G3 and G5 do not apply to them. The registry instead
 * checks a structural invariant: an array's {@link #element()} must resolve to a registered scalar
 * descriptor.
 */
public final class ArrayDescriptor extends PgTypeDescriptor {

  /**
   * The wrapper-to-primitive map for a leaf whose boxed class has a primitive form. A leaf class
   * absent here ({@code String}, {@code byte[]}, {@code BigDecimal}) offers the boxed representation
   * only.
   */
  private static final Map<Class<?>, Class<?>> PRIMITIVE_OF = primitiveMap();

  private final ScalarDescriptor element;
  private final String arrayName;
  private final String arrayFullName;
  private final List<Integer> dims;
  private final Set<LeafRepr> leafReprs;

  /**
   * @param arrayOid the {@code _element} array-type OID (a single one for every dimension)
   * @param arrayName the array type's catalog name, such as {@code _int4}
   * @param arrayFullName the array type's display name, such as {@code int4[]}
   * @param element the scalar element descriptor
   * @param dims the dimensions this array is fuzzed at
   */
  ArrayDescriptor(int arrayOid, String arrayName, String arrayFullName, ScalarDescriptor element,
      int... dims) {
    super(arrayOid);
    this.element = element;
    this.arrayName = arrayName;
    this.arrayFullName = arrayFullName;
    List<Integer> dimList = new ArrayList<>(dims.length);
    for (int dim : dims) {
      dimList.add(dim);
    }
    this.dims = Collections.unmodifiableList(dimList);
    Set<LeafRepr> reprs = EnumSet.of(LeafRepr.BOXED);
    if (PRIMITIVE_OF.containsKey(element.naturalClass())) {
      reprs.add(LeafRepr.PRIMITIVE);
    }
    this.leafReprs = Collections.unmodifiableSet(reprs);
  }

  /**
   * The offline array {@link PgType}: a base type ({@code typtype='b'}) of category {@code 'A'} whose
   * {@code typelem} is the element OID, so the codec resolves the element from the driver's built-in
   * catalog. One {@code PgType} serves every dimension.
   */
  @Override
  public PgType pgType() {
    return new PgType(new ObjectName("pg_catalog", arrayName), arrayFullName, oid(), 'b', 'A', -1,
        element.oid(), 0, 0);
  }

  /** The scalar element type of this array. */
  public ScalarDescriptor element() {
    return element;
  }

  /** The dimensions this array is fuzzed at, {@code {1, 2, 3}}. */
  public List<Integer> dims() {
    return dims;
  }

  /**
   * How a written array is compared with the array read back: {@link Fidelity#DEEP_EQUALS}, which
   * unwraps every dimension and both leaf representations with one comparison.
   */
  public Fidelity fidelity() {
    return Fidelity.DEEP_EQUALS;
  }

  /**
   * The leaf representations this array is fuzzed with: {@link LeafRepr#BOXED} always, plus
   * {@link LeafRepr#PRIMITIVE} when the element has a primitive natural class.
   */
  public Set<LeafRepr> leafReprs() {
    return leafReprs;
  }

  /**
   * The Java leaf class for a representation: the element's natural (boxed) class, or its primitive
   * form.
   *
   * @param leafRepr the leaf representation
   * @return the leaf class ({@code Integer} or {@code int} for an {@code int4} element)
   */
  public Class<?> leafClass(LeafRepr leafRepr) {
    if (leafRepr == LeafRepr.PRIMITIVE) {
      Class<?> primitive = PRIMITIVE_OF.get(element.naturalClass());
      if (primitive == null) {
        throw new IllegalArgumentException(
            "element " + element.naturalClass().getName() + " has no primitive leaf");
      }
      return primitive;
    }
    return element.naturalClass();
  }

  /**
   * The target class for a leaf representation and dimension count: an {@code ndim}-dimensional array
   * of the leaf class. {@code targetClass(BOXED, 2)} is {@code Integer[][]}; {@code targetClass(
   * PRIMITIVE, 2)} is {@code int[][]}.
   *
   * @param leafRepr the leaf representation
   * @param ndim the number of dimensions
   * @return the array target class
   */
  public Class<?> targetClass(LeafRepr leafRepr, int ndim) {
    return Array.newInstance(leafClass(leafRepr), new int[ndim]).getClass();
  }

  private static Map<Class<?>, Class<?>> primitiveMap() {
    Map<Class<?>, Class<?>> map = new LinkedHashMap<>();
    map.put(Integer.class, int.class);
    map.put(Long.class, long.class);
    map.put(Double.class, double.class);
    map.put(Float.class, float.class);
    map.put(Boolean.class, boolean.class);
    map.put(Short.class, short.class);
    return Collections.unmodifiableMap(map);
  }
}
