package com.capicut.audiointel.project

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.utils.ProjectSerializer
import java.io.File
import java.util.UUID

data class LocalProjectMeta(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sourceUri: String?,
    val createdAt: Long,
    val lastModifiedAt: Long,
    val missingMedia: Boolean = false
)

data class ProjectIndex(val items: List<LocalProjectMeta> = emptyList())

/**
 * In-app project library with autosave under filesDir/projects.
 * Complements SAF .lcprj export already in LibreCuts.
 */
class LocalProjectRepository(private val context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val gson: Gson = GsonBuilder().create()

    fun list(): List<LocalProjectMeta> {
        val index = loadIndex()
        var dirty = false
        val refreshed = index.items.map { meta ->
            val missing = isMediaMissing(meta.sourceUri)
            if (missing != meta.missingMedia) {
                dirty = true
                meta.copy(missingMedia = missing)
            } else {
                meta
            }
        }
        if (dirty) persist(ProjectIndex(refreshed))
        return refreshed.sortedByDescending { it.lastModifiedAt }
    }

    fun projectFile(id: String): File = File(root, "$id.lcprj")

    fun projectFileUri(id: String): Uri? {
        val file = projectFile(id)
        if (!file.exists()) return null
        return Uri.fromFile(file)
    }

    fun save(recipe: EditRecipe, existingId: String? = null, displayName: String? = null): LocalProjectMeta {
        val now = System.currentTimeMillis()
        val index = loadIndex()
        val id = existingId ?: UUID.randomUUID().toString()
        val previous = index.items.find { it.id == id }
        val source = recipe.sourceUri.toString()
        val meta = LocalProjectMeta(
            id = id,
            displayName = displayName ?: recipe.projectName.ifBlank { previous?.displayName ?: "Projeto" },
            fileName = "$id.lcprj",
            sourceUri = source,
            createdAt = previous?.createdAt ?: now,
            lastModifiedAt = now,
            missingMedia = isMediaMissing(source)
        )
        try {
            projectFile(id).writeText(ProjectSerializer.serialize(recipe))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to write project file: ${e.message}", e)
        }
        val others = index.items.filterNot { it.id == id }
        persist(ProjectIndex(others + meta))
        return meta
    }

    fun rename(id: String, newName: String) {
        val index = loadIndex()
        val now = System.currentTimeMillis()
        val file = projectFile(id)
        if (file.exists()) {
            try {
                val recipe = ProjectSerializer.deserialize(file.readText())
                file.writeText(
                    ProjectSerializer.serialize(
                        recipe.copy(projectName = newName, lastModifiedAt = now)
                    )
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to rename project file: ${e.message}", e)
            }
        }
        persist(ProjectIndex(index.items.map {
            if (it.id == id) it.copy(displayName = newName, lastModifiedAt = now) else it
        }))
    }

    fun delete(id: String) {
        projectFile(id).delete()
        persist(ProjectIndex(loadIndex().items.filterNot { it.id == id }))
    }

    private fun isMediaMissing(sourceUri: String?): Boolean {
        if (sourceUri.isNullOrBlank()) return true
        return try {
            val uri = Uri.parse(sourceUri)
            when (uri.scheme) {
                "file", null -> {
                    val path = uri.path
                    path.isNullOrBlank() || !File(path).exists()
                }
                "content" -> {
                    context.contentResolver.openInputStream(uri)?.use { true } != true
                }
                else -> false
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun loadIndex(): ProjectIndex {
        if (!indexFile.exists()) return ProjectIndex()
        return runCatching {
            gson.fromJson(indexFile.readText(), ProjectIndex::class.java) ?: ProjectIndex()
        }.getOrElse { ProjectIndex() }
    }

    private fun persist(index: ProjectIndex) {
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(gson.toJson(index))
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }
}
