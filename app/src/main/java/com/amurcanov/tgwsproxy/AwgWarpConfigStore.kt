package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

class AwgWarpProfileRepository(
    private val context: Context,
) {
    companion object {
        private const val DIRECTORY_NAME = "awg-warp"
        private const val PROFILES_DIRECTORY = "profiles"
        private const val STAGING_DIRECTORY = "staging"
        private const val CONFIG_NAME = "profile.conf"
        private const val METADATA_NAME = "metadata.properties"
        private const val SELECTED_NAME = "selected-profile-id"
        private const val LEGACY_CONFIG_NAME = "active.conf"
        private const val MAX_CONFIG_BYTES = 64 * 1024L
        private const val MAX_PROFILE_NAME_LENGTH = 64
    }

    private val root = File(context.filesDir, DIRECTORY_NAME)
    private val profilesRoot = File(root, PROFILES_DIRECTORY)
    private val stagingRoot = File(root, STAGING_DIRECTORY)
    private val selectedFile = File(root, SELECTED_NAME)
    private val legacyConfig = File(root, LEGACY_CONFIG_NAME)

    init {
        ensureDirectories()
        migrateLegacyConfigIfNeeded()
    }

    fun listProfiles(): List<AwgWarpProfileSummary> {
        val selectedId = selectedProfileId()
        return profilesRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { directory -> loadMetadata(directory)?.let { directory to it } }
            .map { (directory, metadata) ->
                val endpoint = readConfig(directory)?.let { text ->
                    AwgWarpConfigParser.parse(text).getOrNull()?.peer?.endpoint
                }
                AwgWarpProfileSummary(
                    metadata = metadata,
                    selected = metadata.id == selectedId,
                    endpoint = endpoint,
                )
            }
            .sortedByDescending { it.metadata.createdAtMs }
            .toList()
    }

    fun selectedProfileId(): String? {
        if (!selectedFile.isFile) return null
        val id = runCatching { selectedFile.readText(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        if (!isValidProfileId(id)) return null
        return id.takeIf { profileConfigFile(it).isFile }
    }

    fun selectedProfile(): AwgWarpProfileSummary? {
        val selectedId = selectedProfileId() ?: return null
        return listProfiles().firstOrNull { it.metadata.id == selectedId }
    }

    fun configPath(): String? {
        val selectedId = selectedProfileId()
        if (selectedId != null) {
            val selectedConfig = profileConfigFile(selectedId)
            if (selectedConfig.isFile && selectedConfig.length() in 1..MAX_CONFIG_BYTES) {
                return selectedConfig.absolutePath
            }
        }
        return legacyConfig.takeIf { it.isFile && it.length() in 1..MAX_CONFIG_BYTES }?.absolutePath
    }

    fun hasConfig(): Boolean = configPath() != null

    fun loadMetadata(profileId: String): AwgWarpProfileMetadata? = loadMetadata(profileDirectory(profileId))

    fun loadConfig(profileId: String): String? = readConfig(profileDirectory(profileId))

    fun loadDetails(profileId: String): Result<AwgWarpConfigDetails> {
        val text = loadConfig(profileId) ?: return Result.failure(IllegalArgumentException("profile_not_found"))
        return AwgWarpConfigParser.parse(text)
    }

    fun readImportedConfig(uri: Uri): Result<String> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_CONFIG_BYTES) error("config_too_large")
                output.write(buffer, 0, read)
            }
            if (total == 0L) error("config_empty")
            output.toString(Charsets.UTF_8.name())
        } ?: error("config_open_failed")
    }

    fun createStagingConfig(content: String): Result<File> = runCatching {
        validateContentSize(content)
        AwgWarpConfigParser.parse(content).getOrThrow()
        ensureDirectory(stagingRoot)
        val file = File(stagingRoot, "${UUID.randomUUID()}.conf")
        atomicWrite(file, content.toByteArray(Charsets.UTF_8))
        file
    }

    fun removeStagingConfig(file: File?) {
        if (file == null) return
        runCatching {
            if (file.parentFile?.canonicalFile == stagingRoot.canonicalFile) file.delete()
        }
    }

    fun saveProfile(
        name: String,
        source: AwgWarpProfileSource,
        configText: String,
        localPublicKey: String? = null,
        health: AwgWarpProfileHealth = AwgWarpProfileHealth.NOT_CHECKED,
        lastCheckedAtMs: Long? = null,
        lastErrorCode: String? = null,
    ): Result<AwgWarpProfileMetadata> = runCatching {
        validateContentSize(configText)
        AwgWarpConfigParser.parse(configText).getOrThrow()
        val id = UUID.randomUUID().toString()
        val directory = profileDirectory(id)
        ensureDirectory(directory)
        val metadata = AwgWarpProfileMetadata(
            id = id,
            name = normalizeName(name),
            source = source,
            createdAtMs = System.currentTimeMillis(),
            localPublicKey = localPublicKey?.takeIf(String::isNotBlank),
            health = health,
            lastCheckedAtMs = lastCheckedAtMs,
            lastErrorCode = lastErrorCode?.takeIf(String::isNotBlank),
        )
        try {
            atomicWrite(profileConfigFile(id), configText.toByteArray(Charsets.UTF_8))
            writeMetadata(directory, metadata)
        } catch (t: Throwable) {
            directory.deleteRecursively()
            throw t
        }
        metadata
    }

    fun importProfile(uri: Uri, name: String): Result<AwgWarpProfileMetadata> {
        return readImportedConfig(uri).fold(
            onSuccess = { text -> saveProfile(name, AwgWarpProfileSource.IMPORTED, text) },
            onFailure = { Result.failure(it) },
        )
    }

    fun selectProfile(profileId: String): Result<Unit> = runCatching {
        val config = profileConfigFile(profileId)
        if (!config.isFile) error("profile_not_found")
        if (!NativeProxy.validateAwgWarpConfig(config.absolutePath)) error("profile_config_invalid")
        atomicWrite(selectedFile, profileId.toByteArray(Charsets.UTF_8))
    }

    fun clearSelection(): Result<Unit> = runCatching {
        if (selectedFile.exists() && !selectedFile.delete()) error("selection_clear_failed")
    }

    fun deleteProfile(profileId: String): Result<Unit> = runCatching {
        val directory = profileDirectory(profileId)
        if (!directory.exists()) return@runCatching
        if (selectedProfileId() == profileId) {
            clearSelection().getOrThrow()
        }
        if (!directory.deleteRecursively()) error("profile_delete_failed")
    }

    fun updateValidation(
        profileId: String,
        health: AwgWarpProfileHealth,
        checkedAtMs: Long = System.currentTimeMillis(),
        errorCode: String? = null,
    ): Result<AwgWarpProfileMetadata> = runCatching {
        val directory = profileDirectory(profileId)
        val current = loadMetadata(directory) ?: error("profile_not_found")
        val updated = current.copy(
            health = health,
            lastCheckedAtMs = checkedAtMs,
            lastErrorCode = errorCode?.takeIf(String::isNotBlank),
        )
        writeMetadata(directory, updated)
        updated
    }

    private fun migrateLegacyConfigIfNeeded() {
        if (!legacyConfig.isFile || listProfileDirectories().isNotEmpty()) return
        val text = runCatching {
            if (legacyConfig.length() !in 1..MAX_CONFIG_BYTES) error("legacy_config_invalid_size")
            legacyConfig.readText(Charsets.UTF_8)
        }.getOrNull() ?: return
        if (AwgWarpConfigParser.parse(text).isFailure) return

        val migrated = saveProfile(
            name = context.getString(R.string.awg_warp_legacy_profile_name),
            source = AwgWarpProfileSource.IMPORTED,
            configText = text,
        ).getOrNull() ?: return
        if (runCatching { atomicWrite(selectedFile, migrated.id.toByteArray(Charsets.UTF_8)) }.isSuccess) {
            legacyConfig.delete()
            File(root, "$LEGACY_CONFIG_NAME.tmp").delete()
        }
    }

    private fun listProfileDirectories(): List<File> = profilesRoot.listFiles().orEmpty().filter(File::isDirectory)

    private fun loadMetadata(directory: File): AwgWarpProfileMetadata? {
        val file = File(directory, METADATA_NAME)
        if (!file.isFile) return null
        val properties = Properties()
        return runCatching {
            file.inputStream().buffered().use(properties::load)
            val id = properties.getProperty("id")?.trim().orEmpty()
            val name = properties.getProperty("name")?.trim().orEmpty()
            if (!isValidProfileId(id) || name.isBlank() || directory.name != id) return@runCatching null
            AwgWarpProfileMetadata(
                id = id,
                name = name,
                source = AwgWarpProfileSource.fromWireValue(properties.getProperty("source")),
                createdAtMs = properties.getProperty("created_at_ms")?.toLongOrNull() ?: 0L,
                localPublicKey = properties.getProperty("local_public_key")?.takeIf(String::isNotBlank),
                health = AwgWarpProfileHealth.fromWireValue(properties.getProperty("health")),
                lastCheckedAtMs = properties.getProperty("last_checked_at_ms")?.toLongOrNull(),
                lastErrorCode = properties.getProperty("last_error_code")?.takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    private fun writeMetadata(directory: File, metadata: AwgWarpProfileMetadata) {
        val properties = Properties().apply {
            setProperty("id", metadata.id)
            setProperty("name", metadata.name)
            setProperty("source", metadata.source.wireValue)
            setProperty("created_at_ms", metadata.createdAtMs.toString())
            metadata.localPublicKey?.let { setProperty("local_public_key", it) }
            setProperty("health", metadata.health.wireValue)
            metadata.lastCheckedAtMs?.let { setProperty("last_checked_at_ms", it.toString()) }
            metadata.lastErrorCode?.let { setProperty("last_error_code", it) }
        }
        val temp = File(directory, "$METADATA_NAME.tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
        replaceFile(temp, File(directory, METADATA_NAME))
    }

    private fun readConfig(directory: File): String? {
        val file = File(directory, CONFIG_NAME)
        if (!file.isFile || file.length() !in 1..MAX_CONFIG_BYTES) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun isValidProfileId(profileId: String): Boolean {
        return runCatching { UUID.fromString(profileId) }.isSuccess
    }

    private fun profileDirectory(profileId: String): File {
        require(isValidProfileId(profileId)) { "invalid_profile_id" }
        return File(profilesRoot, profileId)
    }

    private fun profileConfigFile(profileId: String): File = File(profileDirectory(profileId), CONFIG_NAME)

    private fun ensureDirectories() {
        ensureDirectory(root)
        ensureDirectory(profilesRoot)
        ensureDirectory(stagingRoot)
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) error("storage_directory_create_failed")
    }

    private fun normalizeName(name: String): String {
        val normalized = name.trim().replace(Regex("[\r\n\t]+"), " ").take(MAX_PROFILE_NAME_LENGTH)
        return normalized.ifBlank { context.getString(R.string.awg_warp_default_profile_name) }
    }

    private fun validateContentSize(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "config_empty" }
        require(bytes.size <= MAX_CONFIG_BYTES) { "config_too_large" }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        ensureDirectory(target.parentFile ?: error("storage_parent_missing"))
        val temp = File(target.parentFile, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            replaceFile(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun replaceFile(temp: File, target: File) {
        val backup = File(target.parentFile, "${target.name}.bak")
        backup.delete()
        if (target.exists() && !target.renameTo(backup)) error("storage_backup_failed")
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("storage_replace_failed")
        }
        backup.delete()
    }
}

object AwgWarpConfigStore {
    fun configPath(context: Context): String? = AwgWarpProfileRepository(context).configPath()

    fun hasConfig(context: Context): Boolean = AwgWarpProfileRepository(context).hasConfig()

    /** Backward-compatible import used by the routes screen until it is fully migrated. */
    fun importConfig(context: Context, uri: Uri): Result<Unit> = runCatching {
        val repository = AwgWarpProfileRepository(context)
        val metadata = repository.importProfile(
            uri = uri,
            name = context.getString(R.string.awg_warp_imported_profile_name),
        ).getOrThrow()
        repository.selectProfile(metadata.id).getOrThrow()
    }

    fun remove(context: Context): Boolean {
        val repository = AwgWarpProfileRepository(context)
        val selectedId = repository.selectedProfileId() ?: return true
        return repository.deleteProfile(selectedId).isSuccess
    }
}
