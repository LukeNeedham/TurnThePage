package com.avstaim.listai.core.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Owns only its dedicated directory. External SAF sources are never mutated. */
class PrivateBookStorage(val directory: File) {
    fun file(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9._-]+")) && name != "." && name != "..")
        return File(directory, name)
    }

    fun stage(input: InputStream, maxBytes: Long = 128L * 1024 * 1024): File {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create book storage" }
        val target = file("import-${UUID.randomUUID()}.part")
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Import cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > maxBytes) throw ImportTooLargeException()
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun promote(staged: File, name: String): File = file(name).also { target ->
        check(staged.parentFile == directory && staged.name.endsWith(".part"))
        if (target.exists()) throw IOException("Book file already exists")
        if (!staged.renameTo(target)) throw IOException("Cannot publish book file")
    }

    fun remove(name: String) {
        val target = file(name)
        if (target.exists() && !target.delete()) throw IOException("Cannot delete book file")
    }

    fun reconcile(ownedNames: Set<String>) {
        directory.listFiles()?.filter { it.isFile && it.name !in ownedNames }?.forEach { remove(it.name) }
    }
}

class ImportTooLargeException : IOException("Book exceeds import size limit")
