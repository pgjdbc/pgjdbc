/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.jdbc.PlaceholderStyle;
import org.postgresql.util.GT;
import org.postgresql.util.IntList;
import org.postgresql.util.internal.Nullness;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link org.postgresql.core.Parser} stores information about placeholder occurrences in
 * ParameterContext. In case of standard JDBC placeholders {@code '?'} the position in the SQL text
 * is recorded. For named placeholders {@code ":paramName"} the name is recorded as well as the
 * position. {@code PgPreparedStatement} can then use the name to look up the parameter
 * corresponding index. Native placeholders of the form {@code "$number"} are also supported. These
 * recorded values are also used by toString() methods to provide a human-readable representation of
 * the SQL text.
 */
public class ParameterContext {

  private final PlaceholderStyle allowedPlaceholderStyle;

  public ParameterContext(PlaceholderStyle allowedPlaceholderStyle) {
    this.allowedPlaceholderStyle = allowedPlaceholderStyle;
  }

  public PlaceholderStyle getAllowedPlaceholderStyles() {
    return allowedPlaceholderStyle;
  }

  public enum BindStyle {
    JDBC(false, "?"),
    NAMED(true, ":"),
    NATIVE(true, "$");

    public final boolean isNamedParameter;
    public final String prefix;

    BindStyle(boolean isNamedParameter, String prefix) {
      this.isNamedParameter = isNamedParameter;
      this.prefix = prefix;
    }
  }

  /**
   * EMPTY_CONTEXT is immutable. Calling the add-methods will result in
   * UnsupportedOperationException being thrown.
   */
  public static final ParameterContext EMPTY_CONTEXT = new ParameterContext(PlaceholderStyle.NONE) {
    @Override
    public int addJDBCParameter(int position) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int addNamedParameter(int position, BindStyle bindStyle, String bindName) {
      throw new UnsupportedOperationException();
    }
  };
  static final String uninitializedName = "<UNINITIALIZED>";
  private @Nullable BindStyle bindStyle;
  private @Nullable IntList placeholderPositions;
  private @Nullable List<String> placeholderNames;
  private @Nullable Map<String, Integer> placeholderNameToNativeParameterIndex;
  private @Nullable IntList nativeParameterIndexOfPlaceholderIndex;

  /**
   * Adds a JDBC (positional) parameter to this ParameterContext. Once a positional parameter have been
   * added all subsequent parameters must be positional. Positional parameters cannot be reused, and
   * their order of appearance will correspond to the parameters sent to the PostgreSQL backend.
   *
   * @param position in the SQL text where the parser captured the placeholder.
   * @return 1-indexed position in the order of appearance of positional parameters
   * @throws SQLException if JDBC (positional) and named or native parameters are mixed.
   */
  public int addJDBCParameter(@NonNegative int position) throws SQLException {
    checkAndSetBindStyle(BindStyle.JDBC);

    // There is a 1-1 correspondence between positional and the associated native parameter:
    int nativeParameterIndex = checkAndAddPlaceholderPosition(position);
    return checkAndAddNativeParameterIndexForPlaceholderIndex(nativeParameterIndex);
  }

  public boolean hasParameters() {
    return placeholderPositions != null && !placeholderPositions.isEmpty();
  }

  public boolean hasNamedParameters() {
    return hasParameters() && getBindStyle().isNamedParameter;
  }

  public BindStyle getBindStyle() {
    return checkBindStyleSet();
  }

  /**
   * @param placeholderName name of the placeholder to lookup
   * @return The backend parameter position corresponding to this name
   */
  public @Nullable Integer getNativeParameterIndexForPlaceholderName(
      @NonNull String placeholderName) {
    BindStyle style = getBindStyle();
    if (style == BindStyle.NAMED) {
      if (placeholderNameToNativeParameterIndex == null) {
        return null;
      }
    } else if (style == BindStyle.NATIVE) {
      if (placeholderNameToNativeParameterIndex == null) {
        placeholderNameToNativeParameterIndex = new HashMap<>(placeholderCount());
        for (int i = 0; i < placeholderCount(); i++) {
          Nullness.castNonNull(placeholderNameToNativeParameterIndex)
              .put(NativeQuery.bindName(i + 1), i);
        }
      }
    } else {
      throw new IllegalArgumentException(
          "bindStyle " + bindStyle + " does not support getNativeParameterIndexForPlaceholderName");
    }
    return Nullness.castNonNull(placeholderNameToNativeParameterIndex).get(placeholderName);
  }

  /**
   * @param index 0-indexed position in the order of first appearance
   * @return The name of the placeholder at this backend parameter position
   */
  public String getPlaceholderName(@NonNull Integer index) {
    if (!hasNamedParameters()) {
      throw new IllegalStateException(
          "No placeholder names are available, did you call hasParameters() first?");
    }
    return Nullness.castNonNull(placeholderNames).get(index);
  }

  /**
   * @param index 1-based index of the parameter for which to return a placeholder string.
   * @return Returns the placeholder for the specified position, with the appropriate prefix according to the type of placeholder.
   */
  public String getPlaceholderForToString(@NonNull @Positive Integer index) {
    final BindStyle bindStyle = checkBindStyleSet();

    if (!getBindStyle().isNamedParameter) {
      return bindStyle.prefix;
    }

    if (bindStyle == BindStyle.NAMED) {
      return BindStyle.NAMED.prefix + getPlaceholderName(index - 1);
    } else if (bindStyle == BindStyle.NATIVE) {
      return getPlaceholderName(index - 1);
    }
    throw new IllegalStateException(
        "bindStyle " + bindStyle + " is not not a valid option for getPlaceholderForToString");
  }

  /**
   * @param i 0-indexed position in the order of appearance
   * @return The position of the placeholder in the SQL text for this placeholder index
   */
  public int getPlaceholderPosition(@NonNegative int i) {
    if (placeholderPositions == null) {
      throw new IllegalStateException(
          "No placeholder occurrences are available, did you call hasParameters() first?");
    }
    return placeholderPositions.get(i);
  }

  /**
   * @param i 0-based index of the placeholder occurrence in the SQL text.
   * @return The 0-based index of the native parameter corresponding to the specified placeholder.
   */
  public int getNativeParameterIndexForPlaceholderIndex(@NonNegative int i) {
    IntList nativeParameterIndexOfPlaceholderIndex = this.nativeParameterIndexOfPlaceholderIndex;
    if (nativeParameterIndexOfPlaceholderIndex == null
        || nativeParameterIndexOfPlaceholderIndex.isEmpty()) {
      throw new IllegalStateException(
          "No placeholder indexes are available, did you call hasParameters() first?");
    }
    return nativeParameterIndexOfPlaceholderIndex.get(i);
  }

  public int getLastPlaceholderPosition() {
    IntList placeholderPositions = this.placeholderPositions;
    if (placeholderPositions == null || placeholderPositions.isEmpty()) {
      throw new IllegalStateException("Call hasParameters() first.");
    }
    return placeholderPositions.get(placeholderPositions.size() - 1);
  }

  /**
   * Adds a named parameter to this ParameterContext. Once a named Parameter have been added all
   * subsequent parameters must be named. Using named parameters enable reuse of the same parameters
   * in several locations of the SQL text. The parameters only have to be sent to the PostgreSQL
   * backend once per name specified. The values will be sent in the order of the first appearance
   * of their placeholder.
   *
   * @param position  in the SQL text where the parser captured the placeholder.
   * @param bindStyle is the bindStyle to be used for this parameter, styles can not be mixed in a
   *                  statement.
   * @param bindName  is the name to be used when binding a value for the parameter represented by
   *                  this placeholder.
   * @return 1-indexed position in the order of first appearance of named parameters
   * @throws SQLException if positional and named parameters are mixed.
   */
  public int addNamedParameter(@NonNegative int position, @NonNull BindStyle bindStyle,
      @NonNull String bindName) throws SQLException {
    if (!bindStyle.isNamedParameter) {
      throw new IllegalArgumentException(
          "bindStyle " + bindStyle + " is not not a valid option for addNamedParameter");
    }
    if (bindName.equals(ParameterContext.uninitializedName)) {
      throw new IllegalArgumentException(
          "bindName " + bindName + " is not a valid option for addNamedParameter");
    }
    checkAndSetBindStyle(bindStyle);
    checkAndAddPlaceholderPosition(position);

    // Determine if this bindName already has a corresponding nativeParameterIndex.
    int nativeParameterIndex;

    if (bindStyle == BindStyle.NAMED) {
      if (placeholderNameToNativeParameterIndex == null) {
        placeholderNameToNativeParameterIndex = new HashMap<>();
      }
      nativeParameterIndex = placeholderNameToNativeParameterIndex.computeIfAbsent(bindName, f -> {
        final List<String> placeholderNames = checkAndInitializePlaceholderNames();
        int newIndex = placeholderNames.size();
        placeholderNames.add(bindName);
        return newIndex;
      });
    } else if (bindStyle == BindStyle.NATIVE) {
      nativeParameterIndex = Integer.parseInt(bindName.substring(1)) - 1;
      final List<String> placeholderNames = checkAndInitializePlaceholderNames();
      while (placeholderNames.size() <= nativeParameterIndex) {
        placeholderNames.add(ParameterContext.uninitializedName);
      }
      placeholderNames.set(nativeParameterIndex, bindName);
    } else {
      throw new IllegalArgumentException(
          "bindStyle " + bindStyle + " is not a valid option for addNamedParameter");
    }

    // Associate this occurrence of bindName to the native parameter nativeParameterIndex:
    return checkAndAddNativeParameterIndexForPlaceholderIndex(nativeParameterIndex);
  }

  private List<String> checkAndInitializePlaceholderNames() {
    if (placeholderNames == null) {
      placeholderNames = new ArrayList<>();
    }
    return placeholderNames;
  }

  /**
   * @return Returns the number of placeholder appearances in the SQL text
   */
  public int placeholderCount() {
    return placeholderPositions == null ? 0 : placeholderPositions.size();
  }

  /**
   * @return Returns the number of parameter to be sent to the backend.
   */
  public int nativeParameterCount() {
    if (!hasParameters()) {
      return 0;
    }
    if (getBindStyle().isNamedParameter) {
      // For named parameters, we only need to send a native parameter for each unique name.
      return placeholderNames == null ? 0 : placeholderNames.size();
    } else {
      // If the parameters aren't named we simply return the number of placeholders.
      return placeholderCount();
    }
  }

  /**
   * @return Returns the starting positions of placeholders in the SQL text
   */
  public IntList getPlaceholderPositions() {
    return placeholderPositions == null ? new IntList() : placeholderPositions;
  }

  /**
   * @return Returns an unmodifiableList containing captured placeholder names.
   */
  public List<String> getPlaceholderNames() {
    if (placeholderNames == null) {
      throw new IllegalStateException("Call hasNamedParameters() first.");
    }
    return Collections.unmodifiableList(placeholderNames);
  }

  private void checkAndSetBindStyle(BindStyle bindStyle) throws SQLException {
    if (this.bindStyle == null) {
      this.bindStyle = bindStyle;
    } else if (this.bindStyle != bindStyle) {
      throw new SQLException(GT.tr(
          "Placeholder styles cannot be combined. Saw {0} first but attempting to also use: {1}",
          this.bindStyle, bindStyle));
    }
  }

  private BindStyle checkBindStyleSet() {
    if (bindStyle == null) {
      throw new IllegalStateException("Call hasParameters() first.");
    }
    return bindStyle;
  }

  /**
   * @param position The position in the SQL Text to be registered as the start of a placeholder.
   * @return Returns the index of the registered Placeholder position
   */
  private int checkAndAddPlaceholderPosition(@NonNegative int position) {
    if (hasParameters() && position <= getLastPlaceholderPosition()) {
      throw new IllegalArgumentException("Parameters must be processed in increasing order."
          + "position = " + position + ", LastPlaceholderPosition = "
          + getLastPlaceholderPosition());
    }
    IntList placeholderPositions = this.placeholderPositions;
    if (placeholderPositions == null) {
      this.placeholderPositions = placeholderPositions = new IntList();
    }
    placeholderPositions.add(position);
    return placeholderPositions.size() - 1;
  }

  private int checkAndAddNativeParameterIndexForPlaceholderIndex(
      @Positive int nativeParameterIndex) {
    if (nativeParameterIndexOfPlaceholderIndex == null) {
      nativeParameterIndexOfPlaceholderIndex = new IntList();
    }
    nativeParameterIndexOfPlaceholderIndex.add(nativeParameterIndex);
    return nativeParameterIndex + 1;
  }
}
