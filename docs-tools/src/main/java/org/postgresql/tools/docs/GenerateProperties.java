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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build-time utility: walks the compiled {@code org.postgresql.PGProperty}
 * enum and extracts the {@code @PgApi} / {@code @PgTags} / {@code @PgPropertyType}
 * metadata attached to each constant.
 *
 * <p>This is the scaffold: it reads via ASM and prints a structured
 * summary to stdout so a human (or a follow-up commit) can verify that
 * the bytecode reader picks up everything the source declares. YAML
 * emission, validation, and the friendly fail-mode all land in
 * subsequent commits.
 *
 * <p>Usage from Gradle:
 * <pre>
 *   ./gradlew :docs-tools:generateProperties
 * </pre>
 *
 * <p>The Gradle task passes the path of the compiled :postgresql main
 * classes directory as the first argument so the tool does not need to
 * guess the build layout.
 */
public final class GenerateProperties {

    private static final String PG_PROPERTY_CLASS_RELATIVE_PATH =
        "org/postgresql/PGProperty.class";

    private static final String PG_API_DESC       = "Lorg/postgresql/annotations/PgApi;";
    private static final String PG_API_STATUS_DESC = "Lorg/postgresql/annotations/PgApi$Status;";
    private static final String PG_TAGS_DESC      = "Lorg/postgresql/annotations/PgTags;";
    private static final String PG_TAG_DESC       = "Lorg/postgresql/annotations/PgTags$Tag;";
    private static final String PG_PROPERTY_TYPE_DESC      = "Lorg/postgresql/annotations/PgPropertyType;";
    private static final String PG_PROPERTY_TYPE_KIND_DESC = "Lorg/postgresql/annotations/PgPropertyType$Kind;";
    private static final String DEPRECATED_DESC   = "Ljava/lang/Deprecated;";

    private GenerateProperties() {
        // utility class
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: GenerateProperties <path-to-pgjdbc-main-classes-dir>");
            System.exit(2);
        }

        Path classesDir = Paths.get(args[0]);
        Path pgProperty = classesDir.resolve(PG_PROPERTY_CLASS_RELATIVE_PATH);
        if (!Files.isRegularFile(pgProperty)) {
            System.err.println("PGProperty.class not found at " + pgProperty);
            System.err.println("Make sure :postgresql:classes ran first.");
            System.exit(2);
        }

        List<PropertyRecord> properties = readProperties(pgProperty);

        System.out.printf("Read %d enum constants from %s%n%n",
            properties.size(), pgProperty);

        int annotated = 0;
        for (PropertyRecord p : properties) {
            if (p.isAnnotated()) {
                annotated++;
                System.out.println(p);
            }
        }

        System.out.printf("%nAnnotated: %d / %d (remaining will be addressed in the mass pass).%n",
            annotated, properties.size());
    }

    /** Reads PGProperty.class and returns one record per enum constant. */
    static List<PropertyRecord> readProperties(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        ClassReader reader = new ClassReader(bytes);
        Collector collector = new Collector();
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        return collector.records;
    }

    /**
     * One row's worth of structured metadata extracted from a single
     * enum constant declaration.
     */
    static final class PropertyRecord {
        final String fieldName;     // e.g. "ADAPTIVE_FETCH"
        String status;              // e.g. "STABLE"
        String introducedIn = "";
        String deprecatedIn = "";
        String hiddenIn = "";
        final List<String> tags = new ArrayList<>();
        String type;                // PgPropertyType.Kind value, e.g. "BOOLEAN"
        boolean hasJavaDeprecated;

        PropertyRecord(String fieldName) {
            this.fieldName = fieldName;
        }

        boolean isAnnotated() {
            return status != null || !tags.isEmpty() || type != null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(fieldName).append('\n');
            if (status != null) {
                sb.append("  @PgApi status=").append(status);
                if (!introducedIn.isEmpty()) sb.append(" introducedIn=").append(introducedIn);
                if (!deprecatedIn.isEmpty()) sb.append(" deprecatedIn=").append(deprecatedIn);
                if (!hiddenIn.isEmpty())     sb.append(" hiddenIn=").append(hiddenIn);
                sb.append('\n');
            }
            if (!tags.isEmpty()) {
                sb.append("  @PgTags ").append(tags).append('\n');
            }
            if (type != null) {
                sb.append("  @PgPropertyType ").append(type).append('\n');
            }
            if (hasJavaDeprecated) {
                sb.append("  @Deprecated (JDK)\n");
            }
            return sb.toString();
        }
    }

    /**
     * Walks PGProperty.class. For each enum constant field, peers at the
     * field-level annotations we care about and stuffs the extracted
     * data into a PropertyRecord.
     */
    private static final class Collector extends ClassVisitor {
        final List<PropertyRecord> records = new ArrayList<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc,
                                       String signature, Object value) {
            // Enum constants are emitted as `public static final <EnumType> <NAME>`.
            // Filter to those; ignore the synthetic $VALUES array and any other
            // static fields the compiler may add.
            boolean isStatic   = (access & Opcodes.ACC_STATIC) != 0;
            boolean isFinal    = (access & Opcodes.ACC_FINAL) != 0;
            boolean isEnumConst = (access & Opcodes.ACC_ENUM) != 0;
            if (!(isStatic && isFinal && isEnumConst)) {
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

    /** Reads the four named members of @PgApi(status, introducedIn, deprecatedIn, hiddenIn). */
    private static final class PgApiReader extends AnnotationVisitor {
        final PropertyRecord record;

        PgApiReader(PropertyRecord record) {
            super(Opcodes.ASM9);
            this.record = record;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof String) {
                switch (name) {
                    case "introducedIn": record.introducedIn = (String) value; break;
                    case "deprecatedIn": record.deprecatedIn = (String) value; break;
                    case "hiddenIn":     record.hiddenIn     = (String) value; break;
                    default: /* ignore unknown */
                }
            }
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            if ("status".equals(name) && PG_API_STATUS_DESC.equals(desc)) {
                record.status = value;
            }
        }
    }

    /** Reads the array member of @PgTags(Tag[]). */
    private static final class PgTagsReader extends AnnotationVisitor {
        final PropertyRecord record;

        PgTagsReader(PropertyRecord record) {
            super(Opcodes.ASM9);
            this.record = record;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if (!"value".equals(name)) return null;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visitEnum(String _name, String desc, String value) {
                    if (PG_TAG_DESC.equals(desc)) {
                        record.tags.add(value);
                    }
                }
            };
        }
    }

    /** Reads the single member of @PgPropertyType(Kind). */
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
