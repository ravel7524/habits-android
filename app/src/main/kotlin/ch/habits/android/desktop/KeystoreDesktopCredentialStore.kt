package ch.habits.android.desktop

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import ch.habits.android.core.AcademicPlanCodec
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** Device-only credentials are excluded from the planning snapshot and backups. */
class KeystoreDesktopCredentialStore(file: File) : DesktopCredentialStore {
    private val file = AtomicFile(file)
    private val alias = "habits.desktop.connection.v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        require(create) { "Desktop key is unavailable. Reconnect before sharing." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun read(): DesktopCredential? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 2 * 1024 * 1024) { "Desktop settings are invalid." }; output.write(buffer, 0, count) }
            output.toByteArray().also { require(it.size > 13) }
        }
        val ivSize = bytes[0].toInt() and 255
        require(ivSize == 12 && bytes.size > 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val text = cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
        return Json.decodeFromJsonElement(DesktopCredential.serializer(), AcademicPlanCodec.parseStrictJson(text))
    }
    override fun save(value: DesktopCredential) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(Json.encodeToString(value).toByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    override fun clear() { file.delete() }
}
