package com.rstudio.proxyrec

import com.github.kittinunf.fuel.core.Response
import com.github.kittinunf.fuel.httpGet
import com.google.gson.JsonParser
import com.neovisionaries.ws.client.WebSocket
import com.neovisionaries.ws.client.WebSocketAdapter
import com.neovisionaries.ws.client.WebSocketFactory
import com.neovisionaries.ws.client.WebSocketState
import java.time.Instant

sealed class Event(open val created: Long) {
    open fun sleepBefore(session: ShinySession): Long = 0
    abstract fun handle(session: ShinySession): Any

    companion object {
        fun fromLine(line: String): Event {
            val obj = JsonParser().parse(line).asJsonObject
            val created = Instant.parse(obj.get("created").asString).toEpochMilli()
            val type = obj.get("type").asString
            return when (type) {
                "REQ" -> Http.REQ(created,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "REQ_HOME" -> Http.REQ_HOME(created,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "REQ_SINF" -> Http.REQ_SINF(created,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "REQ_TOK" -> Http.REQ_TOK(created,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "WS_OPEN" -> WS_OPEN(created, obj.get("url").asString)
                "WS_RECV" -> WS_RECV(created, obj.get("message").asString)
                "WS_RECV_INIT" -> WS_RECV_INIT(created, obj.get("message").asString)
                "WS_SEND" -> WS_SEND(created, obj.get("message").asString)
                else -> throw Exception("Unknown event type: $type")
            }
        }
    }

    sealed class Http(override val created: Long,
                      open val url: String,
                      open val method: String,
                      open val statusCode: Int) : Event(created) {

        fun get(session: ShinySession): Response {
            val url = session.replaceTokens(url)
            return (session.httpUrl + url).httpGet().responseString().second.also {
                if (it.statusCode != statusCode)
                    throw java.lang.Exception("Status ${it.statusCode}, expected ${statusCode}")
            }
        }

        class REQ(override val created: Long,
                  override val url: String,
                  override val method: String,
                  override val statusCode: Int) : Http(created, url, method, statusCode) {
            override fun sleepBefore(session: ShinySession) =
                    if (session.webSocket == null) 0 else (created - session.lastEventCreated)

            override fun handle(session: ShinySession) = get(session)
        }

        class REQ_HOME(override val created: Long,
                       override val url: String,
                       override val method: String,
                       override val statusCode: Int) : Http(created, url, method, statusCode) {
            override fun handle(session: ShinySession) {
                val response = get(session)
                val re = """.*<base href="_w_([0-9a-z]+)/.*"""
                        .toRegex(options = setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                val match = re.matchEntire(response.toString())
                val workerId = match?.groupValues?.getOrNull(1)
                session.tokenDictionary["WORKER"] = workerId ?: throw Exception("Unable to parse worker ID from REQ_HOME response.")
            }
        }

        class REQ_SINF(override val created: Long,
                       override val url: String,
                       override val method: String,
                       override val statusCode: Int) : Http(created, url, method, statusCode) {
            override fun handle(session: ShinySession) = get(session)
        }

        class REQ_TOK(override val created: Long,
                      override val url: String,
                      override val method: String,
                      override val statusCode: Int) : Http(created, url, method, statusCode) {
            override fun handle(session: ShinySession) {
                session.tokenDictionary["TOKEN"] = String(get(session).data)
            }
        }
    }

    class WS_OPEN(override val created: Long,
                  val url: String) : Event(created) {
        override fun handle(session: ShinySession) {
            if (session.webSocket != null) throw IllegalStateException("Tried to WS_OPEN but already have a websocket")
            val wsUrl = session.wsUrl + session.replaceTokens(url)
            session.webSocket = WebSocketFactory().createSocket(wsUrl, session.wsConnectTimeoutMs).also {
                it.addListener(object : WebSocketAdapter() {
                    override fun onTextMessage(sock: WebSocket, msg: String) {
                        if (msg.startsWith("a[\"ACK")) {
                            // TODO comprehensive ignore
                            session.log.debug { "%%% Ignoring $msg" }
                        } else {
                            session.log.debug { "%%% Received: $msg" }
                            if (!session.receiveQueue.offer(session.replaceTokens(msg))) {
                                throw Exception("receiveQueue is full (max = ${session.receiveQueueSize})")
                            }
                        }
                    }

                    override fun onStateChanged(websocket: WebSocket?, newState: WebSocketState?) =
                            session.log.debug { "%%% State $newState" }
                })
                it.connect()
            }
        }
    }

    class WS_RECV(override val created: Long,
                  val message: String) : Event(created) {
        override fun handle(session: ShinySession) {
            val receivedStr = session.waitForMessage()
            session.log.debug { "WS_RECV received: $receivedStr" }
            val expectingStr = session.replaceTokens(message)
            val expectingObj = parseMessage(expectingStr)
            if (expectingObj == null) {
                check(expectingStr == receivedStr) {
                    "Expected string $expectingStr but got $receivedStr"
                }
            } else {
                val receivedObj = parseMessage(receivedStr)
                check(expectingObj.keySet() == receivedObj?.keySet()) {
                    "Objects don't have same keys: $expectingObj, $receivedObj"
                }
            }
        }
    }

    class WS_RECV_INIT(override val created: Long,
                       val message: String) : Event(created) {
        override fun handle(session: ShinySession) {
//            session.log.debug { "WS_RECV_INIT handling..." }
//            val receivedStr = session.waitForMessage()
//            session.log.debug { "WS_RECV_INIT received: $receivedStr" }
            TODO("Parse and validate the response and extract SESSION from it.")
        }
    }

    class WS_SEND(override val created: Long,
                  val message: String) : Event(created) {
        override fun sleepBefore(session: ShinySession) =
                created - session.lastEventCreated

        override fun handle(session: ShinySession) {
            val text = session.replaceTokens(message)
            session.webSocket!!.sendText(text)
            session.log.debug { "WS_SEND sent: $text" }
        }
    }
}