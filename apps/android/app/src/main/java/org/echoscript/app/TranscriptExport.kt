package org.echoscript.app

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class ExportFormat(val label: String, val extension: String, val mime: String, val transcript: Boolean = true) {
    CASE("Case package", "echocase", "application/zip", false),
    WAV("Active audio WAV", "wav", "audio/wav", false),
    TXT("Transcript TXT", "txt", "text/plain"),
    JSON("Transcript JSON", "json", "application/json"),
    CSV("Transcript CSV", "csv", "text/csv"),
    HTML("Transcript HTML", "html", "text/html"),
    SRT("Subtitles SRT", "srt", "application/x-subrip"),
    VTT("Subtitles WebVTT", "vtt", "text/vtt"),
    CTM("Word alignment CTM", "ctm", "text/plain"),
    PDF("Processing report PDF", "pdf", "application/pdf"),
    DOCX("Transcript DOCX", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("Transcript XLSX", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

private const val NOTICE = "This report was produced using computer-assisted audio processing and/or transcription. AI-generated transcription requires independent human examiner verification."
private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
private fun speaker(s: JSONObject) = s.optString("speakerLabel", "").ifBlank { if (s.isNull("speaker")) "Unassigned" else s.getString("speaker") }
private fun seconds(value: Double) = String.format(Locale.ROOT, "%.3f", value)
private fun csv(value: String): String {
    val guarded = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
    return "\"${guarded.replace("\"", "\"\"")}\""
}
private fun flags(s: JSONObject) = s.getJSONArray("flags").let { a -> (0 until a.length()).map(a::getString) }
private fun timestamped(s: JSONObject): String = "${clock((s.getDouble("start") * 1000).toLong())}–${clock((s.getDouble("end") * 1000).toLong())} ${speaker(s)}: ${s.getString("text")} [${s.getString("verification")}]"
private fun timing(s: JSONObject, duration: Double) {
    val start = s.getDouble("start"); val end = s.getDouble("end")
    require(start.isFinite() && end.isFinite() && start >= 0 && end > start && end <= duration && flags(s).none { it in setOf("OUT_OF_RANGE", "TIMESTAMP_ANOMALY") }) { "Subtitle or CTM timing is invalid. Review the timestamps or export JSON." }
}

internal fun exportDocument(workflow: OfflineWorkflow, vault: EvidenceVault, manifest: JSONObject, actor: String, format: ExportFormat, destination: OutputStream, cancelled: () -> Boolean): String {
    require(format.transcript)
    val parent = manifest.latestTranscript() ?: error("Create a transcript before exporting it")
    val transcript = workflow.readTranscript(manifest, parent)
    val segments = transcript.items("segments")
    val duration = transcript.optDouble("duration", Double.NaN)
    val verified = if (format == ExportFormat.PDF) vault.verify(manifest, cancelled) else null
    val artifact = vault.addDerivative(manifest, actor, if (format == ExportFormat.PDF) "REPORT" else "DOCUMENT_EXPORT", listOf(parent.getString("id")), "Transcript.${format.extension}", "native-document-export", "1", maxBytes = 32L * 1024 * 1024) { output ->
        fun write(value: String) { if (cancelled()) throw InterruptedException("Export cancelled"); output.write(value.toByteArray(Charsets.UTF_8)) }
        when (format) {
            ExportFormat.JSON -> output.write(EvidenceVault.canonical(transcript))
            ExportFormat.TXT -> { write("EchoScript ${transcript.getString("state")}\n$NOTICE\n\n"); for (s in segments) write(timestamped(s) + "\n") }
            ExportFormat.CSV -> {
                write("start_seconds,end_seconds,speaker,transcript,verification,flags,decoder_score\r\n")
                for (s in segments) write(listOf(seconds(s.getDouble("start")), seconds(s.getDouble("end")), csv(speaker(s)), csv(s.getString("text")), csv(s.getString("verification")), csv(flags(s).joinToString("|")), s.optJSONObject("confidence")?.optDouble("value")?.toString() ?: "").joinToString(",", postfix = "\r\n"))
            }
            ExportFormat.HTML -> {
                write("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>EchoScript transcript</title><h1>EchoScript transcript</h1><p>${xml(NOTICE)}</p><ol>")
                for (s in segments) write("<li>${xml(timestamped(s))}</li>")
                write("</ol></html>")
            }
            ExportFormat.SRT, ExportFormat.VTT -> {
                if (format == ExportFormat.VTT) write("WEBVTT\n\n")
                val delimiter = if (format == ExportFormat.SRT) "," else "."
                for ((index, s) in segments.withIndex()) {
                    timing(s, duration)
                    val start = clock((s.getDouble("start") * 1000).toLong(), delimiter)
                    val end = clock((s.getDouble("end") * 1000).toLong(), delimiter)
                    require(start != end) { "Subtitle segment rounds to zero duration" }
                    val content = "${speaker(s)}: ${s.getString("text")}".replace(Regex("[\\r\\n]+"), " ")
                    write("${index + 1}\n$start --> $end\n${xml(content)}\n\n")
                }
            }
            ExportFormat.CTM -> {
                val caseId = manifest.getString("caseId")
                for (s in segments) {
                    timing(s, duration)
                    val words = s.items("words")
                    require(words.isNotEmpty()) { "CTM needs genuine word timing. Text edits remove stale alignment." }
                    for (w in words) {
                        val start = w.getDouble("start"); val end = w.getDouble("end")
                        val word = w.getString("text")
                        require(start >= 0 && end > start && end <= duration && word.isNotBlank() && word.none(Char::isWhitespace))
                        write("$caseId 1 ${seconds(start)} ${seconds(end - start)} $word\n")
                    }
                }
            }
            ExportFormat.PDF -> renderReport(output, manifest, parent, transcript, checkNotNull(verified), cancelled)
            ExportFormat.DOCX -> officeDocument(output, false, segments, cancelled)
            ExportFormat.XLSX -> officeDocument(output, true, segments, cancelled)
            else -> error("Unsupported document export")
        }
        JSONObject().put("format", format.extension).put("state", transcript.getString("state")).put("transcriptRevision", parent.getString("id"))
    }
    return workflow.exportArtifact(manifest, actor, artifact, destination, cancelled)
}

private fun renderReport(output: OutputStream, manifest: JSONObject, revision: JSONObject, transcript: JSONObject, verified: String, cancelled: () -> Boolean) {
    val pdf = PdfDocument()
    try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
        var number = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, number).create())
        var y = 46f
        fun line(value: String) {
            for (paragraph in value.split('\n')) {
                var position = 0
                while (position < paragraph.length) {
                    if (cancelled()) throw InterruptedException("Report cancelled")
                    if (y > 744) {
                        require(number < 500) { "Report exceeds 500 pages. Use a structured transcript export." }
                        pdf.finishPage(page)
                        page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, ++number).create())
                        y = 46f
                    }
                    val count = paint.breakText(paragraph, position, paragraph.length, true, 530f, null).coerceAtLeast(1)
                    page.canvas.drawText(paragraph, position, position + count, 41f, y, paint)
                    position += count; y += 14
                }
            }
            y += 5
        }
        try {
            line("EchoScript processing report")
            line("${manifest.getString("title")} · ${manifest.getString("caseId")}")
            line("Prepared ${EvidenceVault.utc()} · software ${EvidenceVault.VERSION}")
            line(NOTICE)
            line(verified)
            line("Transcript ${revision.getString("id")} · ${transcript.getString("state")} · ${transcript.getString("provider")} / ${transcript.getString("model")}")
            line("Local recognition input SHA-256 ${transcript.getString("transmissionHash")}")
            for (artifact in manifest.items("artifacts")) line("${artifact.getString("type")} ${artifact.getString("id")}\nSHA-256 ${artifact.getString("sha256")}\n${artifact.getJSONObject("operation")}")
            for (s in transcript.items("segments")) {
                line(timestamped(s))
                if (flags(s).isNotEmpty()) line(flags(s).joinToString(", "))
                for (alt in s.items("alternatives")) line("Provider alternative ${alt.getString("text")} · decoder score ${alt.opt("score")}")
            }
        } finally { pdf.finishPage(page) }
        pdf.writeTo(output)
    } finally { pdf.close() }
}

private fun officeDocument(output: OutputStream, spreadsheet: Boolean, segments: List<JSONObject>, cancelled: () -> Boolean) {
    val kind = if (spreadsheet) "spreadsheetml" else "wordprocessingml"
    val part = if (spreadsheet) "xl/workbook.xml" else "word/document.xml"
    val primaryType = "application/vnd.openxmlformats-officedocument.$kind.${if (spreadsheet) "sheet" else "document"}.main+xml"
    val ns = "http://schemas.openxmlformats.org"
    // Finish the ZIP while leaving the enclosing encrypted artifact stream under vault ownership.
    ZipOutputStream(object : OutputStream() {
        override fun write(value: Int) = output.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
        override fun flush() = output.flush()
    }).use { zip ->
        fun write(text: String) { if (cancelled()) throw InterruptedException("Document export cancelled"); zip.write(text.toByteArray(Charsets.UTF_8)) }
        fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); write(text); zip.closeEntry() }
        val sheetType = if (spreadsheet) "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" else ""
        entry("[Content_Types].xml", "<Types xmlns=\"$ns/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/$part\" ContentType=\"$primaryType\"/>$sheetType</Types>")
        entry("_rels/.rels", "<Relationships xmlns=\"$ns/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$ns/officeDocument/2006/relationships/officeDocument\" Target=\"$part\"/></Relationships>")
        if (spreadsheet) {
            entry(part, "<workbook xmlns=\"$ns/spreadsheetml/2006/main\" xmlns:r=\"$ns/officeDocument/2006/relationships\"><sheets><sheet name=\"Transcript\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            entry("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"$ns/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$ns/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); write("<worksheet xmlns=\"$ns/spreadsheetml/2006/main\"><sheetData>")
            fun row(i: Int, values: List<String>) {
                write("<row r=\"$i\">")
                for ((index, value) in values.withIndex()) write("<c r=\"${'A' + index}$i\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")
                write("</row>")
            }
            row(1, listOf(NOTICE)); row(2, listOf("Start seconds", "End seconds", "Speaker", "Transcript", "Verification"))
            for ((i, s) in segments.withIndex()) row(i + 3, listOf(seconds(s.getDouble("start")), seconds(s.getDouble("end")), speaker(s), s.getString("text"), s.getString("verification")))
            write("</sheetData></worksheet>"); zip.closeEntry()
        } else {
            zip.putNextEntry(ZipEntry(part)); write("<w:document xmlns:w=\"$ns/wordprocessingml/2006/main\"><w:body>")
            fun paragraph(value: String) = write("<w:p><w:r><w:t xml:space=\"preserve\">${xml(value)}</w:t></w:r></w:p>")
            paragraph(NOTICE)
            for (s in segments) paragraph(timestamped(s))
            write("</w:body></w:document>"); zip.closeEntry()
        }
    }
}
