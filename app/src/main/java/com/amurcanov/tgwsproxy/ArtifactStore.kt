package com.amurcanov.tgwsproxy

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets

object ArtifactStore {
    const val UPSTREAM_REPORTS_DIR = "upstream-reports"
    const val RUNTIME_LOGS_DIR = "runtime-logs"

    fun saveTextFile(
        context: Context,
        treeUri: Uri,
        subdirectoryName: String,
        fileName: String,
        text: String,
    ): Uri? {
        return try {
            val directoryUri = getOrCreateTopLevelDirectory(context, treeUri, subdirectoryName) ?: return null
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                directoryUri,
                "text/plain",
                fileName
            ) ?: return null
            context.contentResolver.openOutputStream(fileUri)?.use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
            } ?: return null
            fileUri
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateTopLevelDirectory(
        context: Context,
        treeUri: Uri,
        directoryName: String,
    ): Uri? {
        findTopLevelDirectory(context, treeUri, directoryName)?.let { return it }

        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        return DocumentsContract.createDocument(
            context.contentResolver,
            rootDocumentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName
        )
    }

    private fun findTopLevelDirectory(
        context: Context,
        treeUri: Uri,
        directoryName: String,
    ): Uri? {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val displayNameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(displayNameIndex)
                val mimeType = cursor.getString(mimeTypeIndex)
                if (displayName == directoryName && mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val documentId = cursor.getString(documentIdIndex)
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                }
            }
        }

        return null
    }
}
