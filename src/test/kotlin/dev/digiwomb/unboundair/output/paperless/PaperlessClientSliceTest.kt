package dev.digiwomb.unboundair.output.paperless

import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.documentName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
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
    }

    /** Reads the raw multipart text of a recorded request; the disposition lines are plain text in there. */
    private fun rawBody(request: MockClientHttpRequest): String = request.bodyAsBytes.decodeToString()

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1

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
