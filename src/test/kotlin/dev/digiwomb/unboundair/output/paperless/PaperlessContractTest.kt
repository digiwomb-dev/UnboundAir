package dev.digiwomb.unboundair.output.paperless

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.documentName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * Contract tests for [PaperlessModule] against a real HTTP server (AU-05, AU-06, issue #72).
 *
 * The sibling slice test pins the **request** through Spring's `MockRestServiceServer`, which trusts the
 * client-side encoder. This test puts [WireMockServer] on a dynamic localhost port behind the module and checks
 * what actually went over a socket, and validates the **response** against the paperless-ngx contract.
 *
 * Why the raw body instead of `withRequestBodyPart`: the multipart field names and the `filename=` parameter
 * live inside the encoded body as plain-text `Content-Disposition` lines, and the file-name defect showed up
 * exactly there (a part sent as `name="document"` with no `filename=` at all). Reading
 * `server.allServeEvents.first().request.bodyAsString` asserts on the bytes the server really received, with
 * no multipart-matcher DSL in between; `verify(postRequestedFor(...))` additionally pins path and header
 * through WireMock's own request journal.
 *
 * The successful paperless answer is a **bare JSON string** holding the consumption task UUID
 * (`PostDocumentView.post` ends in `return Response(async_task.id)`), not an object with an `id` field. The
 * schema below encodes that contract; the negative probe proves the schema can actually fail. AU-05 also
 * asks for that UUID to reach the log, which is asserted here rather than in the slice test: the id only
 * exists once a real response body has travelled over the socket.
 *
 * Offline (DC-03): WireMock binds to localhost only; nothing reaches the internet.
 */
class PaperlessContractTest {
    private lateinit var server: WireMockServer

    private val mapper = ObjectMapper()

    private val taskSchema by lazy {
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(mapper.readTree(SCHEMA_JSON))
    }

    @BeforeEach
    fun startServer() {
        server = WireMockServer(options().dynamicPort())
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    @Nested
    inner class RequestOverSocket {
        @Test
        fun `AU-05 upload arrives with path auth header file name and tags`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings(tags = listOf(11L, 22L)))
            val taskId = "8d1c0b2e-1111-2222-3333-444455556666"
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(200).withBody("\"$taskId\"")))

            fixture.module.send(fixture.document)

            server.verify(postRequestedFor(urlEqualTo(POST_PATH)).withHeader("Authorization", equalTo("Token $TOKEN")))
            val raw = rawBody()
            val expectedName = documentName(START, ZONE)
            assertThat(raw)
                .`as`("the multipart document part names its field and carries the derived file name over the socket")
                .contains("name=\"document\"")
                .contains("filename=\"$expectedName\"")
            assertThat(countOccurrences(raw, "name=\"tags\""))
                .`as`("two tag ids travel as two repeated tags fields, not one joined value")
                .isEqualTo(2)
            assertThat(raw).`as`("each tag id travels as its own field value").contains("11").contains("22")
        }
    }

    @Nested
    inner class SuccessfulResponse {
        @Test
        fun `AU-05 the bare task id validates against the schema and completes without throwing`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            val taskId = "8d1c0b2e-1111-2222-3333-444455556666"
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(200).withBody("\"$taskId\"")))

            var thrown: Throwable? = null
            try {
                fixture.module.send(fixture.document)
            } catch (e: Throwable) {
                thrown = e
            }

            assertThat(thrown).`as`("a 2xx answer with the task id must complete without throwing").isNull()
            val errors = taskSchema.validate("\"$taskId\"", InputFormat.JSON)
            assertThat(errors).`as`("the bare UUID string is the contract; it must validate cleanly").isEmpty()
        }

        /**
         * AU-05 asks for the task UUID to appear **in the log** -- that is how an operator
         * follows a document into paperless-ngx after the upload. The sibling test above
         * only proves the module accepts the answer without throwing, which is a different
         * statement: a module that discarded the id entirely would pass it.
         *
         * The id is read back out of Logback rather than compared against a whole expected
         * message, so rephrasing the log line does not break the test while a vanished id
         * still does.
         */
        @Test
        fun `AU-05 the consumption task id appears in the log`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            val taskId = "8d1c0b2e-1111-2222-3333-444455556666"
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(200).withBody("\"$taskId\"")))

            val logger = LoggerFactory.getLogger(PaperlessModule::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                fixture.module.send(fixture.document)
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }

            assertThat(appender.list.map { it.formattedMessage })
                .`as`("the consumption task id must be logged, so a document can be followed into paperless-ngx")
                .anySatisfy { assertThat(it).contains(taskId) }
        }
    }

    @Nested
    inner class SchemaNegativeProbe {
        @Test
        fun `AU-05 a number an id object and a non-uuid string all violate the schema`() {
            val number = taskSchema.validate("42", InputFormat.JSON)
            val idObject = taskSchema.validate("{\"id\": 42}", InputFormat.JSON)
            val notUuid = taskSchema.validate("\"nope\"", InputFormat.JSON)

            println("contract negative probe: number -> ${number.map { it.message }}")
            println("contract negative probe: id-object -> ${idObject.map { it.message }}")
            println("contract negative probe: non-uuid -> ${notUuid.map { it.message }}")

            assertThat(number).`as`("a bare number is not the contracted task id").isNotEmpty
            assertThat(idObject).`as`("an object with an id field is not the contracted task id").isNotEmpty
            assertThat(notUuid).`as`("a string that is not a UUID is not the contracted task id").isNotEmpty
            assertThat(idObject.first().message)
                .`as`("the object violation names the type mismatch so the drift is diagnosable")
                .contains("string")
        }
    }

    @Nested
    inner class FailureStatuses {
        @Test
        fun `AU-06 HTTP 400 makes send throw so the outbox keeps the document`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(400).withBody("bad request")))

            assertThatThrownBy { fixture.module.send(fixture.document) }
                .`as`("HTTP 400 must leave send by throwing; the outbox turns that into a retry")
                .isNotNull()
        }

        @Test
        fun `AU-06 HTTP 500 makes send throw so the outbox keeps the document`(
            @TempDir dir: Path,
        ) {
            val fixture = fixture(dir, settings())
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(500).withBody("boom")))

            assertThatThrownBy { fixture.module.send(fixture.document) }
                .`as`("HTTP 500 must leave send by throwing; the outbox turns that into a retry")
                .isNotNull()
        }
    }

    /** The raw multipart text the server really received; the Content-Disposition lines are plain text in there. */
    private fun rawBody(): String =
        server.allServeEvents
            .first()
            .request.bodyAsString

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1

    private fun settings(tags: List<Long> = emptyList()): PaperlessSettings =
        PaperlessSettings(baseUrl = "http://localhost:${server.port()}", token = TOKEN, tags = tags)

    private fun fixture(
        dir: Path,
        settings: PaperlessSettings,
    ): Fixture {
        val pdf = dir.resolve("document.pdf")
        Files.write(pdf, "%PDF-1.4\n%EOF\n".toByteArray(Charsets.US_ASCII))
        val document = OutputDocument(pdf, 1, START, START.plusSeconds(45))
        // Pinned to HTTP/1.1 because WireMock standalone resets the connection when the JDK client probes for
        // an HTTP/2 upgrade ("Received RST_STREAM: Stream cancelled" on every multipart send). Measured that
        // this is WireMock's limitation and not the module's: the same default RestClient uploads the same
        // multipart body to a plain com.sun.net.httpserver.HttpServer without complaint. Pinning here would
        // otherwise be the dangerous kind of fixture tweak -- it makes the error-status tests pass vacuously,
        // since a transport failure throws just like a rejected upload does.
        val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val factory = JdkClientHttpRequestFactory(httpClient)
        val module = PaperlessModule(settings, ZONE, RestClient.builder().requestFactory(factory).build())
        return Fixture(module, document)
    }

    private data class Fixture(
        val module: PaperlessModule,
        val document: OutputDocument,
    )

    private companion object {
        const val POST_PATH = "/api/documents/post_document/"
        const val TOKEN = "secret-token"
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val START: Instant = Instant.parse("2026-10-03T09:00:00Z")
        val SCHEMA_JSON =
            """
            {"${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
             "type": "string",
             "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}${'$'}"}
            """.trimIndent()
    }
}
