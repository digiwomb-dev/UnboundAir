package dev.digiwomb.unboundair.output.paperless

import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.OutputModule
import dev.digiwomb.unboundair.output.documentName
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.ZoneId
import java.util.UUID
import kotlin.io.path.readBytes

/**
 * Output module for paperless-ngx: uploads the finished document (AU-05) and
 * reports the result the way the outbox needs it (AU-06).
 *
 * [send] reads the PDF behind [OutputDocument.pdf] and POSTs it as the multipart
 * `document` part to `{baseUrl}/api/documents/post_document/`, authenticated as
 * `Authorization: Token ...`. The part file name is the document name from
 * [documentName] -- `scan-YYYYMMDD-HHMMSS.pdf`, rebuilt from [OutputDocument.startedAt]
 * and the zone, because the outbox stores the file as `document.pdf` and the name
 * cannot be read off it (AU-05). Configured `correspondent`, `document_type` and
 * `tags` travel as repeated form fields; `title` and `created` are never sent,
 * because paperless derives them from the file name and the plan fixes that (AU-05).
 *
 * The multipart body is encoded by hand ([multipartBody]) instead of going through
 * Spring's `FormHttpMessageConverter`: the converter streams the body with unknown
 * length, so the HTTP client sends `Transfer-Encoding: chunked` without a
 * `Content-Length` header, and chains that do not de-chunk request bodies hand Django
 * an empty body -- paperless answers HTTP 400 `{"document":["No file was submitted."]}`
 * (issue #219). A pre-built `ByteArray` lets the JDK client send `Content-Length`.
 *
 * A 2xx response carries the paperless-ngx consumption task; its UUID is logged
 * at INFO so an operator can follow the document inside paperless (AU-06). Any
 * non-2xx status and any transport error leave [send] by throwing, which the
 * outbox turns into a retry with backoff (AU-06). The token is written into the
 * request only; logs and exception messages carry the endpoint and status, never
 * the token itself.
 *
 * This is the one main-source package the plan allows Spring in: the REST client
 * arrives as a plain [RestClient] value and the class carries no stereotype, so
 * it stays constructible without a context and registrable through the runtime
 * module selection (AU-03).
 */
class PaperlessModule(
    private val settings: PaperlessSettings,
    private val zone: ZoneId,
    private val restClient: RestClient,
) : OutputModule {
    private val log = LoggerFactory.getLogger(PaperlessModule::class.java)
    private val json = JsonMapper()

    override val name: String = "paperless"

    override fun send(document: OutputDocument) {
        val boundary = "--------------------------" + UUID.randomUUID().toString().replace("-", "")
        val bytes = multipartBody(document, boundary)

        val raw =
            restClient
                .post()
                .uri("${settings.baseUrl}/api/documents/post_document/")
                .header("Authorization", "Token ${settings.token}")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=$boundary"))
                .body(bytes)
                .retrieve()
                .onStatus({ it.isError() }) { _, response ->
                    throw rejected(response)
                }.body(String::class.java)

        logAcceptedTask(raw)
    }

    /**
     * The multipart body for one upload: the `document` part first, then one field
     * per tag id, then `correspondent` and `document_type` when configured, closed
     * by the `--boundary--` terminator. Returned as bytes so the caller can send a
     * known `Content-Length` (issue #219).
     */
    private fun multipartBody(
        document: OutputDocument,
        boundary: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun write(text: String) = out.write(text.toByteArray(HEADER_CHARSET))

        val fileName = documentName(document.startedAt, zone)
        write(
            "--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"$fileName\"\r\n" +
                "Content-Type: application/pdf\r\n\r\n",
        )
        out.write(document.pdf.readBytes())
        write("\r\n")
        settings.tags.forEach { write("--$boundary\r\nContent-Disposition: form-data; name=\"tags\"\r\n\r\n$it\r\n") }
        settings.correspondent?.let { write("--$boundary\r\nContent-Disposition: form-data; name=\"correspondent\"\r\n\r\n$it\r\n") }
        settings.documentType?.let { write("--$boundary\r\nContent-Disposition: form-data; name=\"document_type\"\r\n\r\n$it\r\n") }
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    /**
     * The exception for a non-2xx response. The message names the endpoint and the
     * status and carries a short excerpt of the response body for diagnosis; the
     * token travels only in the request header and must never reach a log line
     * (AU-06).
     */
    private fun rejected(response: ClientHttpResponse): RestClientResponseException {
        val status = response.statusCode
        val body = response.body.readAllBytes()
        val excerpt = body.decodeToString().take(MAX_ERROR_EXCERPT)
        return RestClientResponseException(
            "paperless-ngx rejected the upload: HTTP ${status.value()} from ${settings.baseUrl} -- body: $excerpt",
            status,
            HttpStatus.resolve(status.value())?.reasonPhrase ?: "",
            response.headers,
            body,
            Charsets.UTF_8,
        )
    }

    /**
     * Logs the consumption task of an accepted upload (AU-05).
     *
     * **The body is the bare UUID, not an object.** `PostDocumentView.post` ends in
     * `return Response(async_task.id)`, which DRF renders as a JSON string --
     * `"8d1c0b2e-..."`, quotes included. Reaching for a field named `id` finds nothing
     * and would log "no task id" on every successful upload, which is the kind of
     * false alarm that teaches an operator to ignore the log.
     *
     * The upload has already succeeded by the time this runs, so an unreadable body
     * only degrades to a warning: throwing here would make the outbox retry a document
     * paperless has accepted, and file it twice.
     */
    private fun logAcceptedTask(raw: String?) {
        if (raw.isNullOrBlank()) {
            log.warn("paperless-ngx accepted the document but returned an empty body; no consumption task to log")
            return
        }
        val taskId =
            try {
                val node = json.readTree(raw)
                if (node.isString()) node.stringValue() else null
            } catch (e: JacksonException) {
                log.warn("paperless-ngx accepted the document but the response is not valid JSON; no task to log", e)
                return
            }
        if (taskId.isNullOrBlank()) {
            log.warn("paperless-ngx accepted the document but the response is not a task id: {}", raw.take(MAX_ERROR_EXCERPT))
        } else {
            log.info("paperless-ngx accepted the document; consumption task {}", taskId)
        }
    }

    private companion object {
        /** How much of a rejected response body may travel in the error message. */
        const val MAX_ERROR_EXCERPT = 200

        /** Header text of the multipart body is plain ASCII; ISO-8859-1 keeps it byte-exact. */
        val HEADER_CHARSET: Charset = Charsets.ISO_8859_1
    }
}
