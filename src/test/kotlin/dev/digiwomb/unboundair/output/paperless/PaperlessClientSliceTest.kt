package dev.digiwomb.unboundair.output.paperless

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.documentName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * Slice tests for [PaperlessModule] against a [MockRestServiceServer] (AU-05, AU-06).
 *
 * Only the **request** side matters here: the server mock stands in for
 * paperless-ngx, so the assertions pin down the URL, the `Authorization`
 * header and the multipart body the module sends. The response side -- the
 * bare JSON task id -- belongs to the contract test (#72), which talks to a
 * real HTTP server; here only one non-2xx case proves that [PaperlessModule.send]
 * throws so the outbox keeps the document (AU-06).
 *
 * Why the raw multipart text: asserting on a multipart body through
 * [MockRestServiceServer] has no parsed-structure hook -- the field names and
 * the `filename=` parameter live inside the encoded body. The custom matchers
 * below therefore read the request bytes and assert on the plain-text
 * `Content-Disposition` lines, which is exactly where the file-name defect
 * showed up (a part sent as `name="document"` with no `filename=` at all).
 *
 * Offline (DC-03): no HTTP server, no device, a small synthetic PDF.
 */
class PaperlessClientSliceTest {
    @Nested
    inner class RequestLine {
        @Test
        fun `AU-05 send posts to the post_document endpoint`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 send authenticates with the token scheme`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Token $TOKEN"))
                .andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 the upload declares a multipart body`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    assertThat(request.headers.contentType?.toString() ?: "")
                        .`as`("the form travels as multipart, so paperless sees fields and not a blob")
                        .startsWith("multipart/form-data")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }
    }

    @Nested
    inner class DocumentPart {
        @Test
        fun `AU-05 the document part carries the derived file name`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            val expectedName = documentName(START, ZONE)
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    val body = rawBody(request as MockClientHttpRequest)
                    assertThat(body)
                        .`as`("the document part names its field and its file name")
                        .contains("name=\"document\"")
                        .contains("filename=\"$expectedName\"")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 the file name renders startedAt in the given zone`(
            @TempDir dir: Path,
        ) {
            assertThat(documentName(START, ZONE))
                .`as`("the pinned instant renders in Europe/Berlin time, which the upload must reuse")
                .isEqualTo("scan-20261003-110000.pdf")

            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    assertThat(rawBody(request as MockClientHttpRequest))
                        .`as`("the part file name is rebuilt from startedAt, not read off the stored file")
                        .contains("filename=\"scan-20261003-110000.pdf\"")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 the file name follows the scan timestamp format`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    assertThat(fileNameIn(rawBody(request as MockClientHttpRequest)))
                        .`as`("the part file name keeps the scan-YYYYMMDD-HHMMSS.pdf shape paperless derives the title from")
                        .matches("scan-\\d{8}-\\d{6}\\.pdf")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 the document part carries the pdf bytes`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    assertThat(rawBody(request as MockClientHttpRequest))
                        .`as`("the document part carries the stored bytes, not an empty shell around the right name")
                        .contains("%PDF-1.4")
                        .contains("%EOF")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 title and created are never sent`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(correspondent = 7L, documentType = 3L, tags = listOf(1L, 2L)))
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    val body = rawBody(request as MockClientHttpRequest)
                    assertThat(body)
                        .`as`("paperless derives title and created from the file name, so they must not travel")
                        .doesNotContain("name=\"title\"")
                        .doesNotContain("name=\"created\"")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }
    }

    @Nested
    inner class MetadataFields {
        @Test
        fun `AU-05 tags repeat once per id`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(tags = listOf(11L, 22L)))
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    val body = rawBody(request as MockClientHttpRequest)
                    assertThat(body)
                        .`as`("each tag id travels as its own repeated field, not as one joined value")
                        .contains("\r\n11\r\n")
                        .contains("\r\n22\r\n")
                        .doesNotContain("11,22")
                    assertThat(countOccurrences(body, "name=\"tags\""))
                        .`as`("two tag ids mean exactly two tags fields")
                        .isEqualTo(2)
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 each tag value stays paired with its own field in order`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(tags = listOf(11L, 22L)))
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    assertThat(fieldValues(rawBody(request as MockClientHttpRequest), "tags"))
                        .`as`("swapped or duplicated tag values would file the document under the wrong tags")
                        .containsExactly("11", "22")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 no tags field travels when no tags are configured`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    assertThat(rawBody(request as MockClientHttpRequest))
                        .`as`("an empty tag list means no tags field, not an empty one paperless must interpret")
                        .doesNotContain("name=\"tags\"")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 correspondent and document_type travel when configured`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(correspondent = 7L, documentType = 3L))
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    val body = rawBody(request as MockClientHttpRequest)
                    assertThat(body)
                        .`as`("configured ids travel as plain form fields")
                        .contains("name=\"correspondent\"")
                        .contains("\r\n7\r\n")
                        .contains("name=\"document_type\"")
                        .contains("\r\n3\r\n")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 correspondent and document_type values stay paired with their field`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(correspondent = 7L, documentType = 3L))
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    val body = rawBody(request as MockClientHttpRequest)
                    assertThat(fieldValue(body, "correspondent"))
                        .`as`("the correspondent id must sit in the correspondent field, not the document_type one")
                        .isEqualTo("7")
                    assertThat(fieldValue(body, "document_type"))
                        .`as`("the document type id must sit in the document_type field, not the correspondent one")
                        .isEqualTo("3")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 correspondent travels alone when only it is configured`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(correspondent = 7L))
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    val body = rawBody(request as MockClientHttpRequest)
                    assertThat(fieldValue(body, "correspondent"))
                        .`as`("the configured correspondent id travels on its own")
                        .isEqualTo("7")
                    assertThat(body)
                        .`as`("the unconfigured document type must still stay home")
                        .doesNotContain("name=\"document_type\"")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 document_type travels alone when only it is configured`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(documentType = 3L))
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    val body = rawBody(request as MockClientHttpRequest)
                    assertThat(fieldValue(body, "document_type"))
                        .`as`("the configured document type id travels on its own")
                        .isEqualTo("3")
                    assertThat(body)
                        .`as`("the unconfigured correspondent must still stay home")
                        .doesNotContain("name=\"correspondent\"")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 correspondent and document_type are absent when not configured`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect { request ->
                    assertThat(rawBody(request as MockClientHttpRequest))
                        .`as`("unset ids must not travel, so paperless derives them itself")
                        .doesNotContain("name=\"correspondent\"")
                        .doesNotContain("name=\"document_type\"")
                }.andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }
    }

    @Nested
    inner class TokenSources {
        @Test
        fun `AU-05 a token from a file produces the same header as the inline token`(
            @TempDir dir: Path,
        ) {
            val tokenFile = dir.resolve("token.txt")
            Files.writeString(tokenFile, TOKEN)
            val fromEnv = PaperlessSettings.fromConfigured(BASE_URL, TOKEN, null)
            val fromFile = PaperlessSettings.fromConfigured(BASE_URL, null, tokenFile.toString())

            assertThat(fromFile.token)
                .`as`("both token sources resolve to the one value the upload sends")
                .isEqualTo(fromEnv.token)

            listOf(fromEnv, fromFile).forEach { resolved ->
                val fixture = fixture(dir, resolved)
                fixture.server
                    .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                    .andExpect(header("Authorization", "Token $TOKEN"))
                    .andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

                fixture.module.send(fixture.document)

                fixture.server.verify()
            }
        }

        @Test
        fun `AU-05 a token file with surrounding whitespace resolves trimmed`(
            @TempDir dir: Path,
        ) {
            val tokenFile = dir.resolve("token.txt")
            Files.writeString(tokenFile, "  $TOKEN \n")
            val resolved = PaperlessSettings.fromConfigured(BASE_URL, null, tokenFile.toString())

            assertThat(resolved.token)
                .`as`("a trailing newline from echo must not break the token")
                .isEqualTo(TOKEN)

            val fixture = fixture(dir, resolved)
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(header("Authorization", "Token $TOKEN"))
                .andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 the token file wins over the inline token`(
            @TempDir dir: Path,
        ) {
            val tokenFile = dir.resolve("token.txt")
            Files.writeString(tokenFile, "file-token")
            val resolved = PaperlessSettings.fromConfigured(BASE_URL, "inline-token", tokenFile.toString())

            assertThat(resolved.token)
                .`as`("a replaced file is how a token rotates, so a stale inline token must not shadow it")
                .isEqualTo("file-token")

            val fixture = fixture(dir, resolved)
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(header("Authorization", "Token file-token"))
                .andRespond(withSuccess("\"task-id\"", MediaType.APPLICATION_JSON))

            fixture.module.send(fixture.document)

            fixture.server.verify()
        }

        @Test
        fun `AU-05 a blank token file path falls back to the inline token`() {
            val resolved = PaperlessSettings.fromConfigured(BASE_URL, TOKEN, "   ")

            assertThat(resolved.token)
                .`as`("whitespace is no path, so the configured token still applies")
                .isEqualTo(TOKEN)
        }

        @Test
        fun `AU-05 settings toString never prints the token`() {
            assertThat(settings().toString())
                .`as`("a data class would log the secret into the journal on every print")
                .doesNotContain(TOKEN)
                .contains("***")
        }
    }

    @Nested
    inner class AcceptedResponses {
        @Test
        fun `AU-05 the accepted task id is logged`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            val taskId = "8d1c0b2e-1111-2222-3333-444455556666"
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("\"$taskId\"", MediaType.APPLICATION_JSON))

            val messages = captureModuleLogs { fixture.module.send(fixture.document) }

            assertThat(messages)
                .`as`("the consumption task id must be logged, so a document can be followed into paperless-ngx")
                .anySatisfy { assertThat(it).contains(taskId) }
            fixture.server.verify()
        }

        @Test
        fun `AU-05 an empty acceptance body completes without throwing and warns`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON))

            var thrown: Throwable? = null
            val messages =
                captureModuleLogs {
                    thrown =
                        catchThrowable {
                            fixture.module.send(fixture.document)
                        }
                }

            assertThat(thrown)
                .`as`("the upload already succeeded, so an empty body must not turn into a retry")
                .isNull()
            assertThat(messages)
                .`as`("the missing task id must still be visible to the operator")
                .anySatisfy { assertThat(it).contains("empty body") }
            fixture.server.verify()
        }

        @Test
        fun `AU-05 a non-json acceptance body completes without throwing and warns`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("not-json{{{", MediaType.APPLICATION_JSON))

            var thrown: Throwable? = null
            val messages =
                captureModuleLogs {
                    thrown =
                        catchThrowable {
                            fixture.module.send(fixture.document)
                        }
                }

            assertThat(thrown)
                .`as`("the upload already succeeded, so an unreadable body must not turn into a retry")
                .isNull()
            assertThat(messages)
                .`as`("the unreadable task id must still be visible to the operator")
                .anySatisfy { assertThat(it).contains("not valid JSON") }
            fixture.server.verify()
        }

        @Test
        fun `AU-05 a non-string acceptance body completes without throwing and warns`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("42", MediaType.APPLICATION_JSON))

            var thrown: Throwable? = null
            val messages =
                captureModuleLogs {
                    thrown =
                        catchThrowable {
                            fixture.module.send(fixture.document)
                        }
                }

            assertThat(thrown)
                .`as`("the upload already succeeded, so a body without a task id must not turn into a retry")
                .isNull()
            assertThat(messages)
                .`as`("the unexpected body shape must still be visible to the operator")
                .anySatisfy { assertThat(it).contains("not a task id") }
            fixture.server.verify()
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `AU-06 a rejected upload throws so the outbox keeps the document`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body("nope").contentType(MediaType.TEXT_PLAIN))

            assertThatThrownBy { fixture.module.send(fixture.document) }
                .`as`("a non-2xx response must leave send by throwing; the outbox turns that into a retry")
                .isNotNull()

            fixture.server.verify()
        }

        @Test
        fun `AU-06 a rejected upload names status endpoint and excerpt without the token`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body("nope").contentType(MediaType.TEXT_PLAIN))

            val thrown = catchThrowable { fixture.module.send(fixture.document) }

            assertThat(thrown)
                .`as`("the outbox retry needs an error result that names the failure")
                .isInstanceOf(RestClientResponseException::class.java)
            val failure = thrown as RestClientResponseException
            assertThat(failure.statusCode.value())
                .`as`("the error result carries the rejecting status")
                .isEqualTo(400)
            assertThat(failure.statusText)
                .`as`("the error result carries the reason phrase for the status")
                .contains("Bad Request")
            assertThat(failure.message ?: "")
                .`as`("the message names endpoint and status with a body excerpt for diagnosis")
                .contains("HTTP 400")
                .contains(BASE_URL)
                .contains("nope")
                .doesNotContain(TOKEN)
            fixture.server.verify()
        }

        @Test
        fun `AU-06 a server failure throws with its own status`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body("boom").contentType(MediaType.TEXT_PLAIN))

            val thrown = catchThrowable { fixture.module.send(fixture.document) }

            assertThat(thrown)
                .`as`("HTTP 500 must leave send by throwing; the outbox turns that into a retry")
                .isInstanceOf(RestClientResponseException::class.java)
            val failure = thrown as RestClientResponseException
            assertThat(failure.statusCode.value())
                .`as`("the error result distinguishes a server failure from a rejected request")
                .isEqualTo(500)
            assertThat(failure.message ?: "")
                .`as`("the message names the server failure for diagnosis, still without the token")
                .contains("HTTP 500")
                .contains("boom")
                .doesNotContain(TOKEN)
            fixture.server.verify()
        }

        @Test
        fun `AU-06 a long rejection body is truncated to the excerpt limit`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            val longBody = "e".repeat(300)
            fixture.server
                .expect(requestTo("$BASE_URL/api/documents/post_document/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body(longBody).contentType(MediaType.TEXT_PLAIN))

            val thrown = catchThrowable { fixture.module.send(fixture.document) }

            assertThat(thrown).isInstanceOf(RestClientResponseException::class.java)
            val message = (thrown as RestClientResponseException).message ?: ""
            assertThat(message)
                .`as`("the excerpt keeps the message diagnosable without dragging the whole body along")
                .contains("e".repeat(200))
                .doesNotContain("e".repeat(201))
            fixture.server.verify()
        }
    }

    /** Reads the raw multipart text of a recorded request; the disposition lines are plain text in there. */
    private fun rawBody(request: MockClientHttpRequest): String = request.bodyAsBytes.decodeToString()

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1

    /** The file name the document part was sent with, or `null` when the part names no file at all. */
    private fun fileNameIn(body: String): String? {
        val start = body.indexOf("filename=\"")
        if (start < 0) return null
        val nameStart = start + "filename=\"".length
        val end = body.indexOf('"', nameStart)
        if (end < 0) return null
        return body.substring(nameStart, end)
    }

    /**
     * The value of one form [field] inside the raw multipart [body]: the text between the
     * blank line after its headers and the next line break. `null` when the field is absent.
     */
    private fun fieldValue(
        body: String,
        field: String,
    ): String? = fieldValues(body, field).singleOrNull()

    /** Every value sent for a repeated form [field], in send order. */
    private fun fieldValues(
        body: String,
        field: String,
    ): List<String> {
        val values = mutableListOf<String>()
        var from = 0
        while (true) {
            val nameAt = body.indexOf("name=\"$field\"", from)
            if (nameAt < 0) return values
            val headersEnd = body.indexOf("\r\n\r\n", nameAt)
            if (headersEnd < 0) return values
            val valueEnd = body.indexOf("\r\n", headersEnd + 4)
            if (valueEnd < 0) return values
            values += body.substring(headersEnd + 4, valueEnd)
            from = valueEnd
        }
    }

    /** The log lines [PaperlessModule] wrote while [block] ran. */
    private fun captureModuleLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(PaperlessModule::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun settings(
        correspondent: Long? = null,
        documentType: Long? = null,
        tags: List<Long> = emptyList(),
    ): PaperlessSettings =
        PaperlessSettings(
            baseUrl = BASE_URL,
            token = TOKEN,
            tags = tags,
            correspondent = correspondent,
            documentType = documentType,
        )

    private fun fixture(
        dir: Path,
        settings: PaperlessSettings,
    ): Fixture {
        val pdf = dir.resolve("document.pdf")
        Files.write(pdf, minimalPdfBytes())
        val document = OutputDocument(pdf, 1, START, START.plusSeconds(45))
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val module = PaperlessModule(settings, ZONE, builder.build())
        return Fixture(server, module, document)
    }

    private fun minimalPdfBytes(): ByteArray = "%PDF-1.4\n%EOF\n".toByteArray(Charsets.US_ASCII)

    private data class Fixture(
        val server: MockRestServiceServer,
        val module: PaperlessModule,
        val document: OutputDocument,
    )

    private companion object {
        const val BASE_URL = "http://paperless.test"
        const val TOKEN = "secret-token"
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val START: Instant = Instant.parse("2026-10-03T09:00:00Z")
    }
}
