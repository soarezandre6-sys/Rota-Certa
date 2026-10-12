package com.rotacerta.app.importer

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ImageRouteImporter(private val context: Context) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun readStreetCandidates(
        uri: Uri,
        onSuccess: (List<String>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse {
                onError(it)
                return
            }

        recognizer.process(image)
            .addOnSuccessListener { result ->
                val candidates = result.textBlocks
                    .flatMap { block -> block.lines }
                    .map { it.text.trim() }
                    .filter { it.length in 3..80 }
                    .filterNot { it.all(Char::isDigit) }
                    .distinctBy { it.lowercase() }
                onSuccess(candidates)
            }
            .addOnFailureListener(onError)
    }
}
