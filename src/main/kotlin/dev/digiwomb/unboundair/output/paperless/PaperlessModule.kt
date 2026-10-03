package dev.digiwomb.unboundair.output.paperless

import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.OutputModule
import dev.digiwomb.unboundair.output.documentName
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpResponse
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
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
        val form = LinkedMultiValueMap<String, Any>()
        form.add("document", pdfPart(document))
        if (settings.correspondent != null) form.add("correspondent", settings.correspondent.toString())
        if (settings.documentType != null) form.add("document_type", settings.documentType.toString())
        settings.tags.forEach { form.add("tags", it.toString()) }

        val raw =
            restClient
                .post()
                .uri("${settings.baseUrl}/api/documents/post_document/")
                .header("Authorization", "Token ${settings.token}")
                .body(form)
                .retrieve()
                .onStatus({ it.isError() }) { _, response ->
                    throw rejected(response)
                }.body(String::class.java)

        logAcceptedTask(raw)
    }

    /**
     * The `document` part of the multipart body, carrying the file name AU-05 asks for.
     *
     * The name is rebuilt from [OutputDocument.startedAt] with [documentName]: the outbox
     * stores every document as `document.pdf`, so it cannot be read off the file. It has to
     * reach paperless, which derives the document title from it -- that is why `title` is
     * deliberately not sent.
     *
     * [getFilename] is overridden rather than passed to the constructor. The constructor's
     * second argument is a *description* for error messages only; Spring's form converter
     * reads [org.springframework.core.io.Resource.getFilename], which a plain
     * `ByteArrayResource` answers with `null` -- measured: the part then goes out as
     * `Content-Disposition: form-data; name="document"` with no file name at all, and
     * paperless would file the document under a generated title.
     */
    private fun pdfPart(document: OutputDocument): ByteArrayResource {
        val fileName = documentName(document.startedAt, zone)
        return object : ByteArrayResource(document.pdf.readBytes()) {
            override fun getFilename(): String = fileName
        }
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
    }
}
