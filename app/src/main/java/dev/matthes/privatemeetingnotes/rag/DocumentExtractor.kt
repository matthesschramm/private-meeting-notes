package dev.matthes.privatemeetingnotes.rag

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** Text of a document, page by page where the format has pages. */
data class ExtractedText(val pages: List<String>, val pageCount: Int?)

/** PDF through Android's built-in PdfRenderer text API, Word by reading word/document.xml, text and Markdown as is. */
object DocumentExtractor {
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    val SUPPORTED_TYPES = arrayOf("application/pdf", DOCX, "text/plain", "text/markdown", "text/x-markdown")

    fun extract(file: File): ExtractedText = when (file.extension.lowercase()) {
        "pdf" -> pdf(file)
        "docx" -> docx(file)
        else -> ExtractedText(listOf(file.readText()), null)
    }

    fun extensionFor(mimeType: String?, displayName: String?): String {
        displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in setOf("pdf", "docx", "txt", "md", "markdown") }?.let { return it }
        return when (mimeType) {
            "application/pdf" -> "pdf"
            DOCX -> "docx"
            "text/markdown", "text/x-markdown" -> "md"
            else -> "txt"
        }
    }

    private fun pdf(file: File): ExtractedText =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pages = (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
                }
                ExtractedText(pages, renderer.pageCount)
            }
        }

    /** Paragraphs of a .docx, split into pages where Word last broke the page or at explicit page breaks. */
    private fun docx(file: File): ExtractedText = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: throw IllegalArgumentException("Not a Word document")
        zip.getInputStream(entry).use { input ->
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(input, "UTF-8")
            }
            val pages = mutableListOf<String>()
            val page = StringBuilder()
            var inText = false
            fun breakPage() {
                if (page.isNotBlank()) pages += page.toString().trim()
                page.clear()
            }
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> if (parser.namespace == WORD) when (parser.name) {
                        "t" -> inText = true
                        "tab" -> page.append('\t')
                        "lastRenderedPageBreak" -> breakPage()
                        "br" -> if (parser.getAttributeValue(WORD, "type") == "page") breakPage() else page.append('\n')
                    }
                    XmlPullParser.END_TAG -> if (parser.namespace == WORD) when (parser.name) {
                        "t" -> inText = false
                        "p" -> page.append('\n')
                    }
                    XmlPullParser.TEXT -> if (inText) page.append(parser.text)
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            breakPage()
            ExtractedText(pages, pages.size.takeIf { it > 1 })
        }
    }

    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
}
