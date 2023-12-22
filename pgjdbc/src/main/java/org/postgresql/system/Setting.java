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
package org.postgresql.system;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.CLASS;
import static java.util.stream.Collectors.toSet;

/**
 * Completely defined setting that can be transformed to/from text, use alternate
 * names, carries a description and optional default value.
 *
 * Settings belong to {@link Group groups} that determine where they are
 * valid. Groups can be global or local. If a group is global, all of its settings
 * are global.
 *
 * Each setting requires a "primary" name (available via {@link #getName()}. This name
 * is used when storing settings and when displaying information about the setting.
 *
 * Each setting is allowed to have alternate names that can be used when searching a source
 * for an setting's value. For example, the {@link #getSystem()} method searches for a value
 * in the system properties by looking up the primary name &amp; then alternate names in turn
 * until it finds a non-null value.
 *
 * All settings in global groups are required to have unique names (including their alternate
 * names); it is enforced during instantiation and will throw an exception is duplicates are
 * found.
 *
 *
 * Getting Started:
 *
 * To declare a setting you need 3 things. A factory class, a {@link Group}, and
 * a static field that holds the setting. Because the settings are used by annotation
 * processing this is done via annotations.
 *
 * Factory:
 * A setting factory is a simple class that holds one or more group and setting definitions
 * and is annotated with the {@link Factory} annotation.
 *
 * <code>
 *   \@Setting.Factory
 *   public class MySettings {
 *    // declare groups &amp; settings here
 *   }
 * </code>
 *
 * Group:
 * Each settings is required to belong to a group. Groups are declared using the
 * {@link Group.Info} annotation and the {@link Group#declare()} initializer.
 *
 * <code>
 *   \@Setting.Factory
 *   public class MySettings {
 *
 *    \@Setting.Group.Info(id="my", desc="My Settings")
 *    public static final MY_GROUP = Setting.Group.declare();
 *
 *   }
 * </code>
 *
 * Setting:
 * Now that you have a factory and a group you can define settings. It's done similarly
 * to the group declaration but requires different information.
 *
 * <code>
 *   \@Setting.Factory
 *   public class MySettings {
 *
 *    \@Setting.Group.Info(id="my", desc="My Settings")
 *    public static final MY_GROUP = Setting.Group.declare();
 *
 *    \@Setting.Info(name="a.setting", group="my", desc"A Setting", def="10")
 *    public static final A = Setting.declare()
 *   }
 * </code>
 *
 *
 * Annotation Processing:
 *
 * {@link Setting} &amp; {@link Group} are designed to work with the "settingsgen"
 * annotation processor. It generates documentation and an abstract JDBC datasource
 * named {link com.impossibl.postgres.jdbc.AbstractGeneratedDataSource} from all
 * of the <b>global</b> settings groups.
 *
 * <b>NOTE</b>: Settings can be defined in code without annotations and thus without
 * the support of the annotation processor. This is required for settings with types
 * not supported by the processor.
 *
 *
 * Annotation Supported Types:
 *
 * Although any type can be used with a setting only the following types can be
 * declared via annotations using the annotation processor
 * <ul>
 *   <li>{@link Boolean}</li>
 *   <li>{@link Integer}</li>
 *   <li>{@link String}</li>
 *   <li>Any {@link Enum}</li>
 *   <li>Any {@link Class}</li>
 * </ul>
 *
 * @param <T> Type of the setting
 */
public class Setting<T> {

  /**
   * Setting group.
   *
   * A group can be global or local. All settings in a global group are required
   * to be unique system wide, while settings in a local group only need to be
   * unique within the group. Nothing else is unique about local groups.
   *
   */
  public static class Group {

    /**
     * Group definition annotation.
     *
     * The annotation must be used on a "declared" static final field
     * named inside a setting factory (i.e. a classed annotated with
     * {@link Factory}).
     *
     * A declared group field is one initialized with
     * {@link Group#declare()}.
     *
     * <code>
     * \@Setting.Factory
     * class MySettings {
     *
     *   \@Setting.Group.Info(id="a", desc="A group")
     *   static final A_GROUP = Setting.Group.declare()
     *
     * }
     * </code>

     * @see Setting
     */
    @Target(FIELD)
    @Retention(CLASS)
    public @interface Info {
      String id();
      String desc();
      boolean global() default true;
      int order() default Integer.MAX_VALUE;
    }

    /**
     * Forward declare a group that will be initialized by annotation processing.
     *
     * @see Setting
     * @return Uninitialized group instance
     */
    public static Group declare() {
      return new Group(null, null);
    }

    private static final Map<String, Group> ALL = new LinkedHashMap<>();

    /**
     * Name base map of all defined setting groups
     * @return Map of defined setting groups
     */
    public static Map<String, Group> getAll() {
      return Collections.unmodifiableMap(ALL);
    }

    private String name;
    private String description;
    private boolean global;
    private Map<String, Setting<?>> allNamed = new LinkedHashMap<>();
    private Set<Setting<?>> all = new LinkedHashSet<>();

    public Group(String name, String description) {
      this(name, description, true);
    }

    public Group(String name, String description, boolean global) {
      init(name, description, global);
    }

    /**
     * Initialize a previously {@link #declare() declared} group.
     */
    public void init(String name, String description, boolean global) {
      this.name = name;
      this.description = description;
      this.global = global;
      synchronized (Group.class) {
        ALL.putIfAbsent(name, this);
      }
    }

    /**
     * Adds a previously declared setting to this group.
     *
     * Allows adding a setting with the same name to multiple
     * global groups without conflict.
     */
    public <T> Setting<T> add(Setting<T> setting) {
      synchronized (Setting.class) {
        addAll(allNamed, setting);
        all.add(setting);
      }
      return setting;
    }

    /**
     * Get name of group
     *
     * @return Name of group
     */
    public String getName() {
      return name;
    }

    /**
     * Get description of group
     *
     * @return Description of group
     */
    public String getDescription() {
      return description;
    }

    /**
     * Retrieves a names based map of all settings in the group
     *
     * @return Map of all settings.
     */
    public Map<String, Setting<?>> getAllNamedSettings() {
      return Collections.unmodifiableMap(allNamed);
    }

    /**
     * Retrieves a unique set of all settings owned by the group.
     *
     * @return Set of all settings.
     */
    public Set<Setting<?>> getAllOwnedSettings() {
      return Collections.unmodifiableSet(all).stream().filter(setting -> setting.group == this).collect(toSet());
    }

    /**
     * Retrieves a unique set of all settings in the group.
     *
     * @return Set of all settings.
     */
    public Set<Setting<?>> getAllSettings() {
      return Collections.unmodifiableSet(all);
    }

    @Override
    public String toString() {
      return name;
    }

  }


  /**
   * Setting factory annotation
   *
   * Must be applied to any class that is generating settings via
   * the annotation processor.
   *
   * @see Setting
   */
  @Target(ElementType.TYPE)
  @Retention(CLASS)
  public @interface Factory {
  }

  /**
   * Setting definition annotation.
   *
   * The annotation must be used on a "declared" static final field
   * named inside a setting factory (i.e. a classed annotated with
   * {@link Factory}).
   *
   * A declared setting field is one initialized with {@link Setting#declare()}
   *
   * <code>
   * \@Setting.Factory
   * class MySettings {
   *
   *   \@Setting.Group.Info(id="a", desc="A group")
   *   static final A_GROUP = Setting.Group.declare()
   *
   *   \@Setting.Info(name="a.setting", group="a", desc="A Setting", def="10")
   *   static final A_SETTING = Setting.declare()
   *
   * }
   * </code>
   */
  @Target(FIELD)
  @Retention(CLASS)
  public @interface Info {

  String NO_DEFAULT = "$$$NULL$$$";

    /**
     * Primary name of the setting. Must be in dot-dash format
     * (e.g. <code>this.is.a.setting-name</code>) to ensure it
     * can be translated and used via command line easily.
     */
    String name();

    /**
     * Id of the group to which the setting belongs.
     */
    String group();

    /**
     * Description of the setting. The text can include
     * simple markup allowable in Markdown and JavaDoc.
     */
    String desc();

    /**
     * Static default value of the setting.
     */
    String def() default NO_DEFAULT;

    /**
     * Code that will be directly copied and used as a dynamic default value.
     *
     * When this value is set, {@link #def()} is treated as a description of
     * the dynamic value and {@link #defStatic()} is ignored.
     *
     * @see Setting
     */
    String defDynamic() default NO_DEFAULT;

    /**
     * Code that will be directly copied and used to provide an initial
     * static default value. This should be used to initialize a default
     * value with a non-const variable.
     *
     * When this value is set, {@link #def()} is treated as a description of
     * the dynamic value. If {@link #defDynamic()} is provided this value
     * is ignored.
     *
     * @see Setting
     */
    String defStatic() default NO_DEFAULT;

    /**
     * Minimum allowed value for setting.
     *
     * This value is ignored if the setting's type is not an Integer.
     *
     * @see Setting
     */
    int min() default Integer.MIN_VALUE;

    /**
     * Maximum allowed value for setting.
     *
     * This value is ignored if the setting's type is not an Integer.
     *
     * @see Setting
     */
    int max() default Integer.MAX_VALUE;

    /**
     * Alternate names for the setting.
     *
     * @see Setting
     */
    String[] alternateNames() default {};
  }

  /**
   * Setting value description annotation.
   *
   * Annotation for providing a description for an individual
   * value field (e.g. an enum constants).
   *
   * <code>
   *   enum MyEnum {
   *     \@Setting.Description("This is A value")
   *     A_VALUE
   *   }
   * </code>
   *
   * Descriptions will be used when generating documentation
   * for a fields allowable values.
   */
  @Target(FIELD)
  @Retention(CLASS)
  public @interface Description {
    String value();
  }


  /**
   * String to {@link U} converter functional interface.
   *
   * A simple interface translate string values to a target
   * type while allowing exception to be thrown.
   *
   * @param <U> Destination type of conversion
   */
  public interface Converter<U> {

    U convert(String string) throws Exception;

    static Converter<String> identity() {
      return t -> t;
    }

  }

  /**
   * Forward declare a setting that will be initialized by annotation processing.
   *
   * @see Setting
   * @return Uninitialized setting instance
   */
  public static <U> Setting<U> declare() {
    return new Setting<>(null);
  }

  /**
   * Prefix used when looking up values via {@link System#getProperty(String)}
   */
  private static final String SYSTEM_PROPERTY_PREFIX = "pgjdbc.";

  private Group group;
  private String[] names;
  private Class<? extends T> type;
  private T staticDefaultValue;
  private Supplier<String> dynamicDefaultSupplier;
  private Converter<T> fromString;
  private Function<T, String> toString;
  private String description;

  private Setting(Supplier<String> dynamicDefaultSupplier) {
    this.dynamicDefaultSupplier = dynamicDefaultSupplier;
  }

  /**
   * Constructs a new setting instance with a static default value and without support via the annotation processor.
   *
   * @param group Group the setting belongs to.
   * @param description Description of the setting.
   * @param type Type of the setting.
   * @param defaultValue Default value of the setting in its native type.
   * @param fromString Functional that converts a string to this settings type.
   * @param toString Functional that converts a native setting value to a string.
   * @param names Primary &amp; alternate names for the setting.
   */
  public Setting(Group group, String description, Class<T> type, T defaultValue, Converter<T> fromString, Function<T, String> toString, String[] names) {
    this.staticDefaultValue = defaultValue;
    init(group, description, type, fromString, toString, names);
  }

  /**
   * Constructs a new setting instance with a dynamic default value and without support via the annotation processor.
   *
   * @param group Group the setting belongs to.
   * @param description Description of the setting.
   * @param type Type of the setting.
   * @param dynamicDefaultSupplier Supplier of the dynamic default value for this setting.
   * @param fromString Functional that converts a string to this settings type.
   * @param toString Functional that converts a native setting value to a string.
   * @param names Primary &amp; alternate names for the setting.
   */
  public Setting(Group group, String description, Class<T> type, Supplier<String> dynamicDefaultSupplier, Converter<T> fromString, Function<T, String> toString, String[] names) {
    this.dynamicDefaultSupplier = dynamicDefaultSupplier;
    init(group, description, type, fromString, toString, names);
  }

  private void init(Group group, String description, Class<T> type, Converter<T> fromString, Function<T, String> toString, String[] names) {
    // checkArgument(type != null, "Setting already initialized.");
    if (names.length < 1) throw new IllegalArgumentException("names must not be empty");
    this.group = group;
    this.names = names;
    this.type = type;
    this.fromString = fromString;
    this.toString = toString;
    this.description = description;
    synchronized (Setting.class) {
      if (group.global) {
        if (!isSimpleNameFormat(names[0]))
          throw new IllegalArgumentException(
              "Duplicate setting name found '" + names[0] + "'. " +
                  "Settings in global groups must be unique across all groups."
          );
      }
      addAll(group.allNamed, this);
      group.all.add(this);
    }
  }

  /**
   * Initializes a forward declared setting instance. This is intended for use only by the annotation processor.
   *
   * @param groupId Id of the group the setting belongs to.
   * @param description Description of the setting.
   * @param type Type of the setting.
   * @param fromString Functional that converts a string to this settings type.
   * @param toString Functional that converts a native setting value to a string.
   * @param names Primary &amp; alternate names for the setting.
   */
  public void init(String groupId, String description, Class<T> type, String defaultValue,
                   Converter<T> fromString, Function<T, String> toString, String[] names) {
    Group group = Group.ALL.get(groupId);
    if (group == null) throw new IllegalArgumentException("Unknown group: " + groupId);
    init(group, description, type, fromString, toString, names);
    staticDefaultValue = defaultValue != null ? fromString(defaultValue) : null;
  }

  /**
   * Initializes a forward declared setting instance. This is intended for use only by the annotation processor.
   *
   * @param groupId Id of the group the setting belongs to.
   * @param description Description of the setting.
   * @param type Type of the setting.
   * @param fromString Functional that converts a string to this settings type.
   * @param toString Functional that converts a native setting value to a string.
   * @param names Primary &amp; alternate names for the setting.
   */
  public void init(String groupId, String description, Class<T> type, Supplier<String> defaultValue,
                   Converter<T> fromString, Function<T, String> toString, String[] names) {
    Group group = Group.ALL.get(groupId);
    if (group == null) throw new IllegalArgumentException("Unknown group: " + groupId);
    init(group, description, type, fromString, toString, names);
    dynamicDefaultSupplier = defaultValue;
  }


  private static final Pattern SIMPLE_NAME_PATTERN = Pattern.compile("(?:[a-z][a-z0-9\\-]+)(?:\\.[a-z0-9][a-z0-9\\-]+)*");

  /**
   * Validate primary name as being all lowercase and compatible with generating
   * a JavaBean property name (doesn't start with a number, dash or dot).
   */
  private static boolean isSimpleNameFormat(String name) {
    return SIMPLE_NAME_PATTERN.matcher(name).matches();
  }

  private static void addAll(Map<String, Setting<?>> settings, Setting<?> instance) {
    for (String name : instance.names) {
      if (settings.containsKey(name)) {
        throw new IllegalStateException("Setting with name '" + name + "' already exists");
      }
      settings.put(name, instance);
    }
  }

  /**
   * Retrieve the setting's group
   *
   * @return Group this setting belongs to
   */
  public Group getGroup() {
    return group;
  }

  /**
   * Retrieve the primary name of this setting.
   *
   * @return Primary name of the setting.
   */
  public String getName() {
    return names[0];
  }

  /**
   * Get <b>all</b> names for this setting; including
   * primary and alternate names.
   *
   * @return All names for this setting.
   */
  public String[] getNames() {
    return names;
  }

  /**
   * Retrieve the type of this setting.
   *
   * @return Type of this setting.
   */
  public Class<? extends T> getType() {
    return type;
  }

  /**
   * Flag that tells whether the setting
   * uses a dynamic or static default value.
   *
   * @return <code>true</code> if the default value is dynamic, <code>false</code> otherwise.
   */
  public boolean isDefaultDynamic() {
    return dynamicDefaultSupplier != null;
  }

  /**
   * Retrieve the default value of this setting.
   *
   * @return Default value of this setting.
   */
  public T getDefault() {
    if (isDefaultDynamic()) {
      String value = dynamicDefaultSupplier.get();
      if (value == null) return null;
      return fromString(value);
    }
    return staticDefaultValue;
  }

  /**
   * Retrieve the default value of this setting as text.
   *
   * @return Text version of the default value of this setting.
   */
  public String getDefaultText() {
    if (isDefaultDynamic()) {
      return dynamicDefaultSupplier.get();
    }
    if (staticDefaultValue != null) {
      return toString(staticDefaultValue);
    }
    return null;
  }

  /**
   * Retrieve the description of this setting.
   *
   * @return Description of this setting.
   */
  public String getDescription() {
    return description;
  }

  /**
   * Convert a string to the setting's native type.
   *
   * @param value String value to parse.
   * @return Value in the settings native type.
   * @throws IllegalArgumentException If the value cannot be parsed.
   */
  public T fromString(String value) {
    try {
      return fromString.convert(value);
    }
    catch (Exception e) {
      throw new IllegalArgumentException("Unable to parse setting \"" + getName() + "\" from '" + value + "'");
    }
  }

  /**
   * Convert a native type to a string value.
   *
   * @param value Native value to convert into text.
   * @return Value in text form.
   */
  public String toString(T value) {
    return toString.apply(value);
  }

  /**
   * Looks up the setting in system properties.
   *
   * This method tries all names (primary &amp; alternates) in the
   * order in which they were defined and returns the first
   * non-null value.
   *
   * If no value is found the settings {@link #getDefault() default}
   * value is returned.
   *
   * @return System property value of the setting or its default value.
   */
  public T getSystem() {
    for (String name : names) {
      String value = System.getProperty(SYSTEM_PROPERTY_PREFIX + name);
      if (value != null) {
        return fromString(value);
      }
    }
    return getDefault();
  }

  /**
   * Looks up the setting in the provided {@code properties}.
   *
   * This method tries all names (primary &amp; alternates) in the
   * order in which they were defined and returns the first
   * non-null value.
   *
   * If no value is found the setting's {@link #getDefault()} default)
   * value is returned.
   *
   * @return Property value of the setting or its default value.
   */
  public T get(Properties properties) {
    for (String name : names) {
      String value = properties.getProperty(name);
      if (value != null) {
        return fromString(value);
      }
    }
    return getDefault();
  }

  /**
   * Looks up the setting in the provided {@code properties}, returning
   * it as a text value.
   *
   * This method tries all names (primary &amp; alternates) in the
   * order in which they were defined and returns the first
   * non-null value.
   *
   * If no value is found the setting's {@link #getDefaultText()} default)
   * value is returned.
   *
   * @return Property value of the setting or its default text value.
   */
  public String getText(Properties properties) {
    for (String name : names) {
      String value = properties.getProperty(name);
      if (value != null) {
        return value;
      }
    }
    return getDefaultText();
  }

  @Override
  public String toString() {
    String defaultValue = getDefaultText();
    return group + ": " + Arrays.toString(names) + " (" + type + ") = " + (defaultValue != null ? defaultValue : "null") + " : " + description;
  }

}
