/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package buildlogic

import com.igormaznitsa.jcp.JcpPreprocessor
import com.igormaznitsa.jcp.context.PreprocessorContext
import com.igormaznitsa.jcp.expression.Value
import com.igormaznitsa.jcp.logger.PreprocessorLogger
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import java.io.File
import javax.inject.Inject

open class JavaCommentPreprocessorTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {
    @Internal
    val baseDir = objects.fileProperty()

    @Internal
    val sourceFolders = objects.listProperty<String>()

    @Internal
    val excludedPatterns = objects.setProperty<String>()

    @get:InputFiles
    protected val inputDirectories: FileCollection get() =
        project.files().apply {
            val base = baseDir.get().asFile
            val excludedPatterns = excludedPatterns.get()
            for (folder in sourceFolders.get()) {
                from(project.fileTree(File(base, folder)) {
                    exclude(excludedPatterns)
                })
            }
        }

    @OutputDirectory
    val outputDirectory = objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("jcp/$name"))

    @Input
    val variables = objects.mapProperty<String, String>()

    @Input
    val keepLines = objects.property<Boolean>().convention(true)

    @Input
    val keepComments = objects.property<Boolean>().convention(true)

    @Input
    val clearTarget = objects.property<Boolean>().convention(true)

    @TaskAction
    fun run() {
        val logger = project.logger
        val context = PreprocessorContext(baseDir.get().asFile).apply {
            preprocessorLogger = object : PreprocessorLogger {
                override fun warning(message: String?) = logger.warn(message)
                override fun info(message: String?) = logger.info(message)
                override fun error(message: String?) = logger.error(message)
                override fun debug(message: String?) = logger.debug(message)
            }
            target = outputDirectory.get().asFile
            setSources(sourceFolders.get())
            isClearTarget = clearTarget.get()
            isKeepLines = keepLines.get()
            isKeepComments = keepComments.get()
            excludeFolders = excludedPatterns.get().toList()
            for ((k, v) in variables.get()) {
                setGlobalVariable(k, Value.recognizeRawString(v))
            }
        }
        JcpPreprocessor(context).execute()
    }
}
