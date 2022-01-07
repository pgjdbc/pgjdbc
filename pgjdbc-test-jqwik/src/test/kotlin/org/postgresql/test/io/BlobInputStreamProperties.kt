package org.postgresql.test.io

import net.jqwik.api.Arbitraries.integers
import net.jqwik.api.Arbitraries.just
import net.jqwik.api.Arbitraries.longs
import net.jqwik.api.Arbitraries.oneOf
import net.jqwik.api.Arbitraries.sequences
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.lifecycle.AddLifecycleHook
import net.jqwik.api.lifecycle.PropagationMode
import net.jqwik.api.stateful.ActionSequence
import org.postgresql.PGConnection
import org.postgresql.largeobject.LargeObjectManager
import java.io.ByteArrayInputStream
import java.sql.Connection
import java.util.Random

/**
 * See [jqwik sample](https://jqwik.net/docs/current/user-guide.html#stateful-testing).
 */
@AddLifecycleHook(DbConnectionHook::class, propagateTo = PropagationMode.ALL_DESCENDANTS)
class BlobInputStreamProperties {
    data class InputStreamTestData(
        val dataLength: Int,
        val actions: ActionSequence<InputStreamState>
    )

    @Property
    fun checkMyStack(@ForAll("data") data: InputStreamTestData, con: Connection) {
        // TODO: move contents, expectedBuffer, actualBuffer initialization outside of once per sample
        val rnd = Random()
        val contents = ByteArray(data.dataLength)
        rnd.nextBytes(contents)

        val expectedBuffer = ByteArray(data.dataLength)
        val actualBuffer = ByteArray(data.dataLength)

        con.autoCommit = false
        val lom = con.unwrap(PGConnection::class.java).largeObjectAPI
        val oid = lom.createLO(LargeObjectManager.READWRITE)
        try {
            lom.open(oid).use { blob ->
                blob.write(contents)

                // TODO: should seek(0) be a part of blob.getInputStream?
                blob.seek(0)

                data.actions.run(
                    InputStreamState(
                        actual = blob.inputStream,
                        expected = ByteArrayInputStream(contents),
                        actualBuffer = actualBuffer,
                        expectedBuffer = expectedBuffer
                    )
                )
            }
        } finally {
            lom.unlink(oid)
        }
    }

    @Provide
    fun data(@ForAll("sizes") size: Int) =
        sequences(oneOf(read(), readOffsetLength(size), skip(size.toLong()), mark(size), reset()))
            .map { InputStreamTestData(size, it) }

    @Provide
    fun sizes() = integers().between(0, 20000)

    private fun read() =
        just(InputStreamState.Read())

    private fun readOffsetLength(size: Int) =
        integers().between(0, size).flatMap { offset ->
            integers().between(0, size - offset).map { length ->
                InputStreamState.ReadOffsetLength(offset, length)
            }
        }

    private fun skip(size: Long) =
        longs().between(0, size).map { InputStreamState.Skip(it) }

    private fun mark(size: Int) =
        integers().between(0, size).map { InputStreamState.Mark(it) }

    private fun reset() =
        just(InputStreamState.Reset())
}

