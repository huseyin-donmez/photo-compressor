package com.imageresizer.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Final on-disk verification — the hard guarantee that output ≤ target.
 * Mirrors ios/ImageResizerCore/DiskOutput.swift.
 */
object DiskOutput {
    /**
     * Atomically writes [data] (temp file + rename) and re-checks the real file size.
     * Deletes the file and throws [ResizeError.TargetExceeded] if the guarantee is
     * violated (belt & braces: the search already measured these exact bytes).
     */
    fun writeVerified(data: ByteArray, file: File, targetBytes: Long): Long {
        val parent = file.parentFile
        if (parent != null) parent.mkdirs()
        val tmp = File(parent, file.name + ".tmp")

        try {
            FileOutputStream(tmp).use { it.write(data) }
        } catch (e: IOException) {
            tmp.delete()
            if (isNoSpace(e)) throw ResizeError.StorageFull
            throw ResizeError.OutputSaveFailed
        }

        try {
            // Publish, then stat the real path — exactly what iOS does after its
            // atomic Foundation write.
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            val size = Files.size(file.toPath())
            if (size > targetBytes) {
                file.delete()
                throw ResizeError.TargetExceeded(measured = size, target = targetBytes)
            }
            return size
        } catch (e: ResizeError) {
            throw e
        } catch (e: IOException) {
            tmp.delete()
            file.delete()
            if (isNoSpace(e)) throw ResizeError.StorageFull
            throw ResizeError.OutputSaveFailed
        } catch (e: SecurityException) {
            tmp.delete()
            file.delete()
            throw ResizeError.OutputSaveFailed
        }
    }

    /**
     * ENOSPC (28) / EDQUOT (45): Android surfaces `android.system.ErrnoException`
     * (public `errorCode` field) in the cause chain; the desktop JVM reports it in
     * the message. Keep this best-effort — a miss degrades to OutputSaveFailed.
     */
    private fun isNoSpace(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            try {
                val field = cause.javaClass.getField("errorCode")
                val errno = field.getInt(cause)
                if (errno == 28 || errno == 45) return true
            } catch (_: NoSuchFieldException) {
                // not an ErrnoException — keep walking
            } catch (_: Exception) {
                // reflective access refused — fall through to message check
            }
            val message = cause.message
            if (message != null &&
                (message.contains("No space left") || message.contains("Disk quota"))
            ) return true
            cause = cause.cause
        }
        return false
    }
}
