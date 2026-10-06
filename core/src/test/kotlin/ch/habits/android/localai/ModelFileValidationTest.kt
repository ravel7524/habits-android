package ch.habits.android.localai

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class ModelFileValidationTest {
    private val bytes = "GGUFsynthetic-model-bytes".toByteArray()
    private fun descriptor(data: ByteArray = bytes) = ModelDescriptor("fixture", "Synthetic", "https://example.invalid/model", "fixture.gguf", data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }, "https://example.invalid/LICENSE")
    private fun file(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("model-validation-").toFile()
        try { block(java.io.File(directory, "fixture.gguf").apply { writeBytes(bytes) }) }
        finally { directory.deleteRecursively() }
    }
    @Test fun matchingSizeMagicAndFullChecksumAreRequired() = file { file ->
        var verified = 0L
        ModelFileValidation.verify(file, descriptor(), progress = { verified = it })
        assertEquals(bytes.size.toLong(), verified)
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertFailsWith<ModelValidationException> { ModelFileValidation.verify(file, descriptor()) }
    }
    @Test fun sameSizedCorruptionAndWrongMagicNeverPass() = file { file ->
        val corrupted = bytes.clone().apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }
        file.writeBytes(corrupted)
        assertFailsWith<ModelValidationException> { ModelFileValidation.verify(file, descriptor()) }
        val wrongMagic = bytes.clone().apply { this[0] = 72 }
        file.writeBytes(wrongMagic)
        assertFailsWith<ModelValidationException> { ModelFileValidation.verify(file, descriptor(wrongMagic)) }
    }
    @Test fun cancellationIsCheckedDuringVerification() = file { file ->
        assertFailsWith<IllegalStateException> { ModelFileValidation.verify(file, descriptor(), checkCancellation = { error("Synthetic cancel") }) }
        assertContentEquals(bytes, file.readBytes(), "Verification cancellation cannot modify a model")
    }
    @Test fun symlinksAndUnsafeFileNamesAreRejected() = file { file ->
        val link = java.io.File(file.parentFile, "link.gguf")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        assertFailsWith<ModelValidationException> { ModelFileValidation.verify(link, descriptor()) }
        for (name in listOf("../private.gguf", "nested/model.gguf", ".hidden.gguf")) {
            assertFailsWith<IllegalArgumentException> { descriptor().copy(fileName = name).validateFileMetadata() }
        }
    }
}
