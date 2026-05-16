/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

/**
 * ASM-based reader that produces one [PropertyRecord] per enum constant
 * in `PGProperty.class`, populated with the metadata carried by
 * `@PgApi`, `@PgTags`, `@PgPropertyType`, and the plain `@Deprecated` marker.
 * All four annotations live with CLASS retention and are invisible to
 * runtime reflection — so we read the bytecode directly.
 *
 * Runtime details (`name`, `default`, `description`, `choices`,
 * `required`) are added by [ReflectionEnricher] — that data is in normal
 * fields, accessible via reflection, and far cheaper to pull that way
 * than to reverse-engineer from `<clinit>`.
 */
internal object ClassFileReader {

    private const val PG_API_DESC        = "Lorg/postgresql/annotations/PgApi;"
    private const val PG_API_STATUS_DESC = "Lorg/postgresql/annotations/PgApi\$Status;"
    private const val PG_TAGS_DESC       = "Lorg/postgresql/annotations/PgTags;"
    private const val PG_TAG_DESC        = "Lorg/postgresql/annotations/PgTags\$Tag;"
    private const val PG_PROPERTY_TYPE_DESC       = "Lorg/postgresql/annotations/PgPropertyType;"
    private const val PG_PROPERTY_TYPE_KIND_DESC  = "Lorg/postgresql/annotations/PgPropertyType\$Kind;"
    private const val DEPRECATED_DESC    = "Ljava/lang/Deprecated;"

    fun read(classFile: Path): List<PropertyRecord> {
        val collector = Collector()
        ClassReader(Files.readAllBytes(classFile))
            .accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)
        return collector.records
    }

    private class Collector : ClassVisitor(Opcodes.ASM9) {
        val records: MutableList<PropertyRecord> = mutableListOf()

        override fun visitField(
            access: Int,
            name: String,
            desc: String?,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            // Enum constants only; skip $VALUES and other synthesised statics.
            if (access and Opcodes.ACC_ENUM == 0) return null

            val record = PropertyRecord(name).also(records::add)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                    when (descriptor) {
                        PG_API_DESC  -> PgApiReader(record)
                        PG_TAGS_DESC -> PgTagsReader(record)
                        PG_PROPERTY_TYPE_DESC -> PgPropertyTypeReader(record)
                        DEPRECATED_DESC -> {
                            record.hasJavaDeprecated = true
                            null
                        }
                        else -> null
                    }
            }
        }
    }

    /** Reads the four named members of @PgApi. */
    private class PgApiReader(private val record: PropertyRecord) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) {
            val s = value as? String ?: return
            when (name) {
                "introducedIn" -> record.introducedIn = s
                "deprecatedIn" -> record.deprecatedIn = s
                "hiddenIn"     -> record.hiddenIn = s
                // ignore any unknown member added later
            }
        }

        override fun visitEnum(name: String?, desc: String?, value: String?) {
            if (name == "status" && desc == PG_API_STATUS_DESC) {
                record.status = value
            }
        }
    }

    /** Reads the array member of @PgTags. */
    private class PgTagsReader(private val record: PropertyRecord) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visitArray(name: String?): AnnotationVisitor? {
            if (name != "value") return null
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visitEnum(unused: String?, desc: String?, value: String?) {
                    if (desc == PG_TAG_DESC && value != null) {
                        record.tags.add(value)
                    }
                }
            }
        }
    }

    /** Reads the single member of @PgPropertyType. */
    private class PgPropertyTypeReader(private val record: PropertyRecord) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visitEnum(name: String?, desc: String?, value: String?) {
            if (name == "value" && desc == PG_PROPERTY_TYPE_KIND_DESC) {
                record.type = value
            }
        }
    }
}
