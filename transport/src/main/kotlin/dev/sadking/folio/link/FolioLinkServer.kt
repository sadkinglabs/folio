package dev.sadking.folio.link

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharsetIfNeeded
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One request Folio Link answered. No headers, credentials or bodies are kept. */
data class LinkEvent(
    val time: Long,
    val method: String,
    val route: String,
    val status: Int,
    val remote: String,
)

/**
 * Folio Link's HTTP server on the local network: KOReader's Progress sync, its OPDS catalogue
 * browser and the Folio plugin talk to it. [LinkRoutes] decides every answer.
 */
class FolioLinkServer(
    private val routes: LinkRoutes,
    private val onEvent: (LinkEvent) -> Unit = {},
) {
    /** Progress sync only, with no library behind it. */
    constructor(
        kosync: Kosync,
        onEvent: (LinkEvent) -> Unit = {},
    ) : this(LinkRoutes(kosync, null, { null }), onEvent)

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** Starts listening on [port] (0 picks a free one) and returns the port in use. */
    suspend fun start(port: Int, host: String = "0.0.0.0"): Int {
        stop()
        val created =
            embeddedServer(CIO, port = port, host = host) {
                intercept(ApplicationCallPipeline.Call) {
                    val method = call.request.httpMethod
                    val path = call.request.uri.substringBefore('?').trimEnd('/')
                    // Book uploads stream to disk; every other body is read as text.
                    val upload = method == HttpMethod.Put && path == SyncRoutes.UPLOAD_ROUTE
                    val limit =
                        when {
                            upload -> SyncRoutes.MAX_UPLOAD
                            path.startsWith("/folio/v2/") -> SyncRoutes.MAX_BODY.toLong()
                            else -> Kosync.MAX_BODY.toLong()
                        }
                    val length = call.request.headers["Content-Length"]?.toLongOrNull() ?: 0
                    val response =
                        if (length > limit)
                            LinkResponse.Text(
                                413,
                                LinkRoutes.JSON,
                                """{"code":2003,"message":"Invalid request"}""",
                            )
                        else
                            withContext(Dispatchers.IO) {
                                val request =
                                    KosyncRequest(
                                        method = method.value,
                                        path = call.request.uri,
                                        headers =
                                            call.request.headers.names().associateWith {
                                                call.request.headers[it].orEmpty()
                                            },
                                        body =
                                            if (
                                                !upload &&
                                                    (method == HttpMethod.Post ||
                                                        method == HttpMethod.Put)
                                            )
                                                call.receiveText()
                                            else null,
                                        remote = call.request.local.remoteAddress,
                                    )
                                if (upload) call.receiveStream().use { routes.handle(request, it) }
                                else routes.handle(request)
                            }
                    response.headers.forEach { (name, value) -> call.response.header(name, value) }
                    when (response) {
                        is LinkResponse.Text ->
                            call.respondText(
                                response.body,
                                ContentType.parse(response.type)
                                    .withCharsetIfNeeded(Charsets.UTF_8),
                                HttpStatusCode.fromValue(response.status),
                            )
                        is LinkResponse.Download -> {
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(
                                        ContentDisposition.Parameters.FileName,
                                        response.name,
                                    )
                                    .toString(),
                            )
                            call.respond(
                                LocalFileContent(response.file, ContentType.parse(response.type))
                            )
                        }
                    }
                    onEvent(
                        LinkEvent(
                            System.currentTimeMillis(),
                            method.value,
                            route(call.request.uri),
                            response.status,
                            call.request.local.remoteAddress,
                        )
                    )
                    finish()
                }
            }
        created.start(wait = false)
        server = created
        return created.engine.resolvedConnectors().first().port
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 2000)
        server = null
    }

    companion object {
        /** The route without the document key, so logs say what happened, not which book. */
        fun route(uri: String): String {
            val path = uri.substringBefore('?')
            return if (path.startsWith("/syncs/progress/")) "/syncs/progress/…" else path
        }
    }
}
