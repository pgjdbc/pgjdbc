/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ASM-based reader that produces one {@link PropertyRecord} per enum
 * constant in {@code PGProperty.class}, populated with the metadata
 * carried by {@code @PgApi}, {@code @PgTags}, {@code @PgPropertyType}, and the
 * plain {@code @Deprecated} marker. All four annotations live with
 * CLASS retention and are invisible to runtime reflection — so we read
 * the bytecode directly.
 *
 * <p>Runtime details ({@code name}, {@code default}, {@code description},
 * {@code choices}, {@code required}) are added by {@link ReflectionEnricher}
 * — that data is in normal fields, accessible via reflection, and far
 * cheaper to pull that way than to reverse-engineer from {@code <clinit>}.
 */
final class ClassFileReader {

    private static final String PG_API_DESC        = "Lorg/postgresql/annotations/PgApi;";
    private static final String PG_API_STATUS_DESC = "Lorg/postgresql/annotations/PgApi$Status;";
    private static final String PG_TAGS_DESC       = "Lorg/postgresql/annotations/PgTags;";
    private static final String PG_TAG_DESC        = "Lorg/postgresql/annotations/PgTags$Tag;";
    private static final String PG_PROPERTY_TYPE_DESC       = "Lorg/postgresql/annotations/PgPropertyType;";
    private static final String PG_PROPERTY_TYPE_KIND_DESC  = "Lorg/postgresql/annotations/PgPropertyType$Kind;";
    private static final String DEPRECATED_DESC    = "Ljava/lang/Deprecated;";

    private ClassFileReader() {
        // utility class
    }

    static List<PropertyRecord> read(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        ClassReader reader = new ClassReader(bytes);
        Collector collector = new Collector();
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return collector.records;
    }

    private static final class Collector extends ClassVisitor {
        final List<PropertyRecord> records = new ArrayList<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                                       String signature, Object value) {
            // Enum constants only; skip $VALUES and other synthesised statics.
            boolean isEnumConst = (access & Opcodes.ACC_ENUM) != 0;
            if (!isEnumConst) {
                return null;
            }

            PropertyRecord record = new PropertyRecord(name);
            records.add(record);

            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (PG_API_DESC.equals(descriptor)) {
                        return new PgApiReader(record);
                    }
                    if (PG_TAGS_DESC.equals(descriptor)) {
                        return new PgTagsReader(record);
                    }
                    if (PG_PROPERTY_TYPE_DESC.equals(descriptor)) {
                        return new PgPropertyTypeReader(record);
                    }
                    if (DEPRECATED_DESC.equals(descriptor)) {
                        record.hasJavaDeprecated = true;
                    }
                    return null;
                }
            };
        }
    }

    /** Reads the four named members of @PgApi. */
    private static final class PgApiReader extends AnnotationVisitor {
        final PropertyRecord record;

        PgApiReader(PropertyRecord record) {
            super(Opcodes.ASM9);
            this.record = record;
        }

        @Override
        public void visit(String name, Object value) {
            if (!(value instanceof String)) {
                return;
            }
            switch (name) {
                case "introducedIn":
                    record.introducedIn = (String) value;
                    break;
                case "deprecatedIn":
                    record.deprecatedIn = (String) value;
                    break;
                case "hiddenIn":
                    record.hiddenIn = (String) value;
                    break;
                default:
                    /* ignore unknown */
            }
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            if ("status".equals(name) && PG_API_STATUS_DESC.equals(desc)) {
                record.status = value;
            }
        }
    }

    /** Reads the array member of @PgTags. */
    private static final class PgTagsReader extends AnnotationVisitor {
        final PropertyRecord record;

        PgTagsReader(PropertyRecord record) {
            super(Opcodes.ASM9);
            this.record = record;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (!"value".equals(name)) {
                return null;
            }
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visitEnum(String unused, String desc, String value) {
                    if (PG_TAG_DESC.equals(desc)) {
                        record.tags.add(value);
                    }
                }
            };
        }
    }

    /** Reads the single member of @PgPropertyType. */
    private static final class PgPropertyTypeReader extends AnnotationVisitor {
        final PropertyRecord record;

        PgPropertyTypeReader(PropertyRecord record) {
            super(Opcodes.ASM9);
            this.record = record;
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            if ("value".equals(name) && PG_PROPERTY_TYPE_KIND_DESC.equals(desc)) {
                record.type = value;
            }
        }
    }
}
