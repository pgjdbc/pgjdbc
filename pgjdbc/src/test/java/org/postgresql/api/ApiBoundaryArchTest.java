/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Guards the codec SPI boundary: the public {@code org.postgresql.api} packages must not depend on
 * the driver internals in {@code org.postgresql.jdbc} or {@code org.postgresql.core}. The dependency
 * direction is one-way — {@code jdbc} implements the {@code api} interfaces ({@code PgType},
 * {@code PgCodecContext}), so {@code jdbc} -&gt; {@code api} stays legal.
 *
 * <p>The check runs on bytecode, so it catches more than an import scan: a fully qualified reference
 * with no {@code import} (the leak fixed in slice 2b, where {@code org.postgresql.jdbc.BooleanTypeUtil}
 * was called by its full name), a dependency through a method signature, a field type, or an
 * annotation all count. A grep over {@code import} lines misses every one of those.</p>
 */
class ApiBoundaryArchTest {

  private static final JavaClasses API_CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("org.postgresql.api..");

  @Test
  void apiDoesNotDependOnDriverInternals() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("org.postgresql.api..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.postgresql.jdbc..",
            "org.postgresql.core..")
        .because(
            "org.postgresql.api is the public codec SPI and must not depend on driver internals: "
                + "it has to compile and run without org.postgresql.jdbc/org.postgresql.core (the "
                + "offline and COPY paths rely on this), and the dependency direction is one-way "
                + "(jdbc implements the api interfaces). To use data from an internal type, expose it "
                + "through an api interface (as TypeDescriptor and CodecContext do) and implement that "
                + "in jdbc; do not reference jdbc/core from api");
    rule.check(API_CLASSES);
  }
}
