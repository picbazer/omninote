package com.example.util

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

data class PdfExtractionResult(
    val title: String,
    val text: String,
    val pageCount: Int,
    val charCount: Int
)

object PdfTextExtractor {

    suspend fun extractTextFromUri(context: Context, uri: Uri): PdfExtractionResult = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri)
        val cleanTitle = fileName.substringBeforeLast(".pdf").replace('_', ' ').replace('-', ' ').trim()

        val tempFile = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.pdf")
        var pageCount = 1

        try {
            // Copy URI content to temporary file for analysis
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Inspect page count using Android's native PdfRenderer
            try {
                ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                    }
                }
            } catch (_: Exception) {
                // Ignore renderer inspection errors on unusual PDF structures
            }

            // Read raw bytes for pure-Android text stream parsing
            val pdfBytes = tempFile.readBytes()
            val extractedText = parsePdfContentStreams(pdfBytes)

            val finalText = if (extractedText.isNotBlank()) {
                cleanExtractedText(extractedText)
            } else {
                buildFallbackDocumentText(cleanTitle, pageCount)
            }

            PdfExtractionResult(
                title = cleanTitle.ifBlank { "Imported Notebook" },
                text = finalText,
                pageCount = pageCount,
                charCount = finalText.length
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "Document.pdf"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: name
                }
            }
        } catch (_: Exception) {
            name = uri.lastPathSegment ?: "Document.pdf"
        }
        return name
    }

    private fun parsePdfContentStreams(bytes: ByteArray): String {
        val result = StringBuilder()
        val streamTag = "stream".toByteArray(StandardCharsets.ISO_8859_1)
        val endStreamTag = "endstream".toByteArray(StandardCharsets.ISO_8859_1)

        var searchIndex = 0
        while (searchIndex < bytes.size) {
            val streamStart = indexOf(bytes, streamTag, searchIndex)
            if (streamStart == -1) break

            // Skip "stream" and subsequent newline (\r\n or \n)
            var dataStart = streamStart + streamTag.size
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++

            val streamEnd = indexOf(bytes, endStreamTag, dataStart)
            if (streamEnd == -1) break

            // Examine stream dictionary before stream token to check if FlateDecode
            val dictStartIndex = (streamStart - 200).coerceAtLeast(0)
            val headerText = String(bytes, dictStartIndex, streamStart - dictStartIndex, StandardCharsets.ISO_8859_1)
            val isFlate = headerText.contains("/FlateDecode")

            val streamLen = streamEnd - dataStart
            if (streamLen > 0) {
                try {
                    val decompressedBytes = if (isFlate) {
                        decompressFlate(bytes, dataStart, streamLen)
                    } else {
                        bytes.copyOfRange(dataStart, streamEnd)
                    }

                    if (decompressedBytes != null && decompressedBytes.isNotEmpty()) {
                        val text = extractTextFromStream(decompressedBytes)
                        if (text.isNotBlank()) {
                            result.append(text).append("\n\n")
                        }
                    }
                } catch (_: Exception) {
                    // Skip damaged or encrypted streams gracefully
                }
            }

            searchIndex = streamEnd + endStreamTag.size
        }

        // If streams yielded nothing (e.g. uncompressed raw text outside standard streams), search raw bytes
        if (result.isBlank()) {
            val rawText = extractRawTextStrings(bytes)
            if (rawText.isNotBlank()) {
                result.append(rawText)
            }
        }

        return result.toString().trim()
    }

    private fun decompressFlate(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        return try {
            val bais = ByteArrayInputStream(bytes, offset, length)
            val inflaterStream = InflaterInputStream(bais)
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var read: Int
            while (inflaterStream.read(buffer).also { read = it } != -1) {
                baos.write(buffer, 0, read)
            }
            baos.toByteArray()
        } catch (_: Exception) {
            // Try raw inflater without header
            try {
                val inflater = Inflater(true)
                inflater.setInput(bytes, offset, length)
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count <= 0) break
                    baos.write(buffer, 0, count)
                }
                inflater.end()
                baos.toByteArray()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun extractTextFromStream(streamBytes: ByteArray): String {
        val streamContent = String(streamBytes, StandardCharsets.ISO_8859_1)
        val textBuilder = StringBuilder()

        // Match BT ... ET blocks or standalone Tj / TJ operators
        val btRegex = Regex("""BT\s*(.*?)\s*ET""", RegexOption.DOT_MATCHES_ALL)
        val matches = btRegex.findAll(streamContent)

        var foundBlock = false
        for (match in matches) {
            foundBlock = true
            val block = match.groupValues[1]
            parseTextOperators(block, textBuilder)
            textBuilder.append("\n")
        }

        if (!foundBlock) {
            // Try parsing text operators directly in stream content
            parseTextOperators(streamContent, textBuilder)
        }

        return textBuilder.toString()
    }

    private fun parseTextOperators(content: String, out: StringBuilder) {
        var i = 0
        val len = content.length
        var lastWasNewline = false

        while (i < len) {
            // Check for literal string ( ... ) Tj or ' or "
            if (content[i] == '(') {
                val endParen = findMatchingParen(content, i)
                if (endParen != -1) {
                    val literal = content.substring(i + 1, endParen)
                    val decoded = decodePdfLiteralString(literal)

                    // Peek ahead for operator
                    val rest = content.substring(endParen + 1, (endParen + 20).coerceAtMost(len))
                    val opMatch = Regex("""^\s*(Tj|'|")""").find(rest)
                    if (opMatch != null) {
                        if (opMatch.value.contains("'") || opMatch.value.contains("\"")) {
                            if (!lastWasNewline) out.append("\n")
                        }
                        out.append(decoded).append(" ")
                        lastWasNewline = false
                    }
                    i = endParen + 1
                    continue
                }
            }

            // Check for array TJ [ ... ] TJ
            if (content[i] == '[') {
                val endBracket = content.indexOf(']', i)
                if (endBracket != -1) {
                    val arrayContent = content.substring(i + 1, endBracket)
                    val rest = content.substring(endBracket + 1, (endBracket + 10).coerceAtMost(len))
                    if (Regex("""^\s*TJ""").containsMatchIn(rest)) {
                        parseTjArray(arrayContent, out)
                        out.append(" ")
                    }
                    i = endBracket + 1
                    continue
                }
            }

            // Check for hex string < ... > Tj
            if (content[i] == '<' && (i + 1 < len && content[i + 1] != '<')) {
                val endHex = content.indexOf('>', i)
                if (endHex != -1) {
                    val hexContent = content.substring(i + 1, endHex).trim()
                    val rest = content.substring(endHex + 1, (endHex + 10).coerceAtMost(len))
                    if (Regex("""^\s*(Tj|'|")""").containsMatchIn(rest)) {
                        val decoded = decodeHexPdfString(hexContent)
                        if (decoded.isNotBlank()) {
                            out.append(decoded).append(" ")
                        }
                    }
                    i = endHex + 1
                    continue
                }
            }

            // Check for line break operators T*, Td, TD
            if (content[i] == 'T' && i + 1 < len) {
                val nextChar = content[i + 1]
                if (nextChar == '*' || nextChar == 'd' || nextChar == 'D' || nextChar == 'm') {
                    if (!lastWasNewline && out.isNotEmpty()) {
                        out.append("\n")
                        lastWasNewline = true
                    }
                }
            }

            i++
        }
    }

    private fun parseTjArray(arrayContent: String, out: StringBuilder) {
        var idx = 0
        val len = arrayContent.length
        while (idx < len) {
            if (arrayContent[idx] == '(') {
                val endP = findMatchingParen(arrayContent, idx)
                if (endP != -1) {
                    val str = arrayContent.substring(idx + 1, endP)
                    out.append(decodePdfLiteralString(str))
                    idx = endP + 1
                    continue
                }
            } else if (arrayContent[idx] == '<' && idx + 1 < len && arrayContent[idx + 1] != '<') {
                val endH = arrayContent.indexOf('>', idx)
                if (endH != -1) {
                    val hex = arrayContent.substring(idx + 1, endH).trim()
                    out.append(decodeHexPdfString(hex))
                    idx = endH + 1
                    continue
                }
            } else if (arrayContent[idx] == '-' || arrayContent[idx].isDigit()) {
                // Spacing number: negative offsets represent spaces in standard PDF fonts
                var numEnd = idx
                while (numEnd < len && (arrayContent[numEnd] == '-' || arrayContent[numEnd] == '.' || arrayContent[numEnd].isDigit())) {
                    numEnd++
                }
                val numStr = arrayContent.substring(idx, numEnd)
                val spacing = numStr.toDoubleOrNull()
                if (spacing != null && spacing < -120.0) {
                    if (out.isNotEmpty() && !out.endsWith(" ")) {
                        out.append(" ")
                    }
                }
                idx = numEnd
                continue
            }
            idx++
        }
    }

    private fun findMatchingParen(s: String, startIdx: Int): Int {
        var depth = 0
        var escaped = false
        for (i in startIdx until s.length) {
            val c = s[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun decodePdfLiteralString(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '(' -> sb.append('(')
                    ')' -> sb.append(')')
                    '\\' -> sb.append('\\')
                    in '0'..'7' -> {
                        // Octal sequence \ddd
                        var octalLen = 1
                        while (octalLen < 3 && i + 1 + octalLen < raw.length && raw[i + 1 + octalLen] in '0'..'7') {
                            octalLen++
                        }
                        val octalStr = raw.substring(i + 1, i + 1 + octalLen)
                        val code = octalStr.toIntOrNull(8) ?: 32
                        sb.append(code.toChar())
                        i += octalLen
                    }
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeHexPdfString(hex: String): String {
        val cleanHex = hex.replace(" ", "")
        if (cleanHex.length % 2 != 0) return ""

        return try {
            if (cleanHex.startsWith("FEFF", ignoreCase = true) || (cleanHex.length >= 4 && cleanHex.length % 4 == 0 && cleanHex.startsWith("00"))) {
                // UTF-16BE
                val sb = StringBuilder()
                var i = if (cleanHex.startsWith("FEFF", ignoreCase = true)) 4 else 0
                while (i + 4 <= cleanHex.length) {
                    val code = cleanHex.substring(i, i + 4).toInt(16)
                    sb.append(code.toChar())
                    i += 4
                }
                sb.toString()
            } else {
                // Latin1 / ASCII
                val bytes = ByteArray(cleanHex.length / 2)
                for (i in bytes.indices) {
                    val byteVal = cleanHex.substring(i * 2, i * 2 + 2).toInt(16)
                    bytes[i] = byteVal.toByte()
                }
                String(bytes, StandardCharsets.ISO_8859_1)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractRawTextStrings(bytes: ByteArray): String {
        val content = String(bytes, StandardCharsets.ISO_8859_1)
        val sb = StringBuilder()
        val regex = Regex("""\(([^\\)]|\\.)*\)\s*(Tj|')""")
        for (match in regex.findAll(content)) {
            val raw = match.value.substringBeforeLast("Tj").substringBeforeLast("'").trim()
            if (raw.startsWith("(") && raw.endsWith(")")) {
                val decoded = decodePdfLiteralString(raw.substring(1, raw.length - 1))
                if (decoded.length > 1) {
                    sb.append(decoded).append(" ")
                }
            }
        }
        return sb.toString()
    }

    private fun cleanExtractedText(raw: String): String {
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
    }

    private fun buildFallbackDocumentText(title: String, pageCount: Int): String {
        return """
            # $title
            
            [Imported PDF Document]
            Total Pages: $pageCount
            
            This document has been imported into OmniNote AI. While the document appears to consist primarily of scanned pages or graphical vector plates, you can:
            - Add your own notes, summaries, or meeting transcripts right here.
            - Ask questions in the AI Assistant tab to explore concepts or brainstorm ideas!
        """.trimIndent()
    }

    private fun indexOf(source: ByteArray, target: ByteArray, fromIndex: Int): Int {
        if (fromIndex >= source.size || target.isEmpty()) return -1
        outer@ for (i in fromIndex..(source.size - target.size)) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
