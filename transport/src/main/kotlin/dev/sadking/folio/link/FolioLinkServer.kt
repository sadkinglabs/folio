package dev.sadking.folio.link

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText

/** One request Folio Link answered. No headers, credentials or bodies are kept. */
data class LinkEvent(
    val time: Long,
    val method: String,
    val route: String,
    val status: Int,
    val remote: String,
)

/**
 * Folio Link's HTTP server: KOReader's Progress sync talks to it over the local network. It only
 * answers the kosync routes; anything else is a 404.
 */
class FolioLinkServer(private val kosync: Kosync, private val onEvent: (LinkEvent) -> Unit = {}) {
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** Starts listening on [port] (0 picks a free one) and returns the port in use. */
    suspend fun start(port: Int, host: String = "0.0.0.0"): Int {
        stop()
        val created =
            embeddedServer(CIO, port = port, host = host) {
                intercept(ApplicationCallPipeline.Call) {
                    val method = call.request.httpMethod
                    val length = call.request.headers["Content-Length"]?.toLongOrNull() ?: 0
                    val response =
                        if (length > Kosync.MAX_BODY)
                            KosyncResponse(413, """{"code":2003,"message":"Invalid request"}""")
                        else
                            kosync.handle(
                                KosyncRequest(
                                    method = method.value,
                                    path = call.request.uri,
                                    headers =
                                        call.request.headers.names().associateWith {
                                            call.request.headers[it].orEmpty()
                                        },
                                    body =
                                        if (method == HttpMethod.Post || method == HttpMethod.Put)
                                            call.receiveText()
                                        else null,
                                    remote = call.request.local.remoteAddress,
                                )
                            )
                    call.respondText(
                        response.body,
                        ContentType.Application.Json,
                        HttpStatusCode.fromValue(response.status),
                    )
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
