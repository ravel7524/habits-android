package ch.habits.android.localai

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class ModelValidationException(message: String) : IOException(message)

object ModelFileValidation {
    /** Full verification, never only existence/size. Caller performs this on IO. */
    fun verify(file: File, descriptor: ModelDescriptor, checkCancellation: () -> Unit = {}, progress: (Long) -> Unit = {}) {
        descriptor.validateFileMetadata()
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() != descriptor.size) {
            throw ModelValidationException("The model does not have its expected file size.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val magic = ByteArray(4)
            var readMagic = 0
            while (readMagic < magic.size) {
                checkCancellation()
                val count = input.read(magic, readMagic, magic.size - readMagic)
                if (count < 0) throw ModelValidationException("The model header is incomplete.")
                readMagic += count
            }
            if (!magic.contentEquals(byteArrayOf(71, 71, 85, 70))) throw ModelValidationException("The model is not a GGUF file.")
            digest.update(magic)
            var received = 4L
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                received += count
                if (received > descriptor.size) throw ModelValidationException("The model exceeds its expected size.")
                digest.update(buffer, 0, count)
                progress(received)
            }
            if (received != descriptor.size) throw ModelValidationException("The model file changed during verification.")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), descriptor.sha256.toByteArray(Charsets.US_ASCII))) {
            throw ModelValidationException("The model checksum does not match the official file.")
        }
        checkCancellation()
    }
}
