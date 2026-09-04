package snap

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import sun.misc.{Signal, SignalHandler}

/** SPEC §7.9 / §9 — Snap's intentionally small HTTP boundary.
  *
  * The server accepts a serialized repository snapshot, so it never rereads the working directory after startup. The client is similarly narrow: one exact GET,
  * with redirects disabled, followed by normal typed validation.
  */
object Http:

  final class RunningServer private[Http] (val url: String, private val server: HttpServer):
    def stop(): Unit = server.stop(0)

  /** Starts a loopback-only snapshot server without waiting for a signal.
    *
    * Exposed for focused tests; process commands normally use [[serve]].
    */
  def start(repository: Repository, port: Int): Either[SnapError, RunningServer] =
    try
      val snapshot = RepositoryJson.serialize(repository).getBytes(StandardCharsets.UTF_8)
      val server   = HttpServer.create(
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
        0
      )
      server.createContext("/repository.json", SnapshotHandler(snapshot))
      server.start()
      val actualPort = server.getAddress.getPort
      Right(new RunningServer(s"http://127.0.0.1:$actualPort/repository.json", server))
    catch
      case error: IOException       => Left(networkError(error))
      case error: SecurityException => Left(networkError(error))

  /** Announces the plain URL only after startup succeeds, then blocks until SIGINT or SIGTERM has stopped the server. Both signals return normally so the CLI
    * exits with status 0 rather than the shell's signal status.
    */
  def serve(repository: Repository, port: Int, announce: String => Unit): Either[SnapError, Unit] =
    start(repository, port).map: running =>
      announce(Presentation.serveUrl(running.url))
      awaitSignal(running)

  /** Performs exactly one GET of `location`; redirects are deliberately never followed. Parsing and validation mirror the local repository reader.
    */
  def fetchRepository(location: String): Either[SnapError, Repository] =
    try
      val request  = HttpRequest.newBuilder(URI.create(location)).GET().build()
      val client   = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      if response.statusCode != 200 then Left(SnapError(s"HTTP ${response.statusCode}"))
      else parseRepository(response.body)
    catch
      case _: IllegalArgumentException => Left(SnapError(s"invalid HTTP repository URL: ${escape(location)}"))
      case error: IOException          => Left(networkError(error))
      case _: InterruptedException     =>
        Thread.currentThread.interrupt()
        Left(SnapError("HTTP request interrupted"))

  private def parseRepository(body: String): Either[SnapError, Repository] =
    RepositoryJson
      .parse(body)
      .left
      .map: error =>
        error.detail.stripPrefix("malformed JSON:") match
          case detail if detail != error.detail => SnapError(s"invalid JSON:$detail")
          case _                                => error
      .flatMap(repository => Validation.validate(repository).map(_ => repository))

  private final class SnapshotHandler(snapshot: Array[Byte]) extends HttpHandler:
    override def handle(exchange: HttpExchange): Unit =
      try
        val request = exchange.getRequestURI
        if request.getRawQuery != null || request.getRawPath != "/repository.json" then sendEmpty(exchange, 404)
        else
          exchange.getRequestMethod match
            case "GET"  => sendSnapshot(exchange, snapshot, includeBody = true)
            case "HEAD" => sendSnapshot(exchange, snapshot, includeBody = false)
            case _      =>
              exchange.getResponseHeaders.set("Allow", "GET, HEAD")
              sendEmpty(exchange, 405)
      finally exchange.close()

  private def sendSnapshot(exchange: HttpExchange, snapshot: Array[Byte], includeBody: Boolean): Unit =
    val headers = exchange.getResponseHeaders
    headers.set("Content-Type", "application/json; charset=utf-8")
    headers.set("Content-Length", snapshot.length.toString)
    if includeBody then
      exchange.sendResponseHeaders(200, snapshot.length.toLong)
      val body = exchange.getResponseBody
      try body.write(snapshot)
      finally body.close()
    else exchange.sendResponseHeaders(200, -1)

  private def sendEmpty(exchange: HttpExchange, status: Int): Unit =
    exchange.sendResponseHeaders(status, -1)

  private def awaitSignal(running: RunningServer): Unit =
    val stopped = CountDownLatch(1)
    val handler = new SignalHandler:
      override def handle(signal: Signal): Unit =
        running.stop()
        stopped.countDown()

    val term    = Signal("TERM")
    val int     = Signal("INT")
    val oldTerm = Signal.handle(term, handler)
    val oldInt  = Signal.handle(int, handler)
    try stopped.await()
    finally
      Signal.handle(term, oldTerm)
      Signal.handle(int, oldInt)

  private def networkError(error: Exception): SnapError =
    val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
    SnapError(s"HTTP error: ${escape(detail)}")

  private def escape(text: String): String =
    text.flatMap:
      case '\\'                   => "\\\\"
      case '\t'                   => "\\t"
      case '\n'                   => "\\n"
      case char if char.isControl => f"\\u${char.toInt}%04x"
      case char                   => char.toString
