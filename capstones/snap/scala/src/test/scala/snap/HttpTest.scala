package snap

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.{InetAddress, InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.SortedMap

/** Focused examples for SPEC §7.9 / §9 beyond the process-level public cases. */
class HttpTest extends munit.FunSuite:

  private val author     = ContributorId("a@x")
  private val file       = TrackedPath("file.txt")
  private val patch      = Patch(author, 1, Version.empty, "one", Vector(Change.Text(file, Vector(EditOp.Insert(Vector("one\n"))))))
  private val repository = Repository(
    Repository.format,
    Version(SortedMap(author -> 1L)),
    Vector(patch)
  )

  private val client = HttpClient.newHttpClient()

  private def request(uri: String, method: String = "GET"): HttpRequest =
    val builder = HttpRequest.newBuilder(URI.create(uri))
    method match
      case "GET"  => builder.GET().build()
      case "HEAD" => builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
      case "POST" => builder.POST(HttpRequest.BodyPublishers.noBody()).build()

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val output = exchange.getResponseBody
    try output.write(bytes)
    finally
      output.close()
      exchange.close()

  test("snapshot server serves GET and HEAD, rejects queries, and reports unsupported methods"):
    val running = Http.start(repository, 0).toOption.getOrElse(fail("server did not start"))
    try
      val get = client.send(request(running.url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      assertEquals(get.statusCode, 200)
      assertEquals(get.headers.firstValue("Content-Type").get, "application/json; charset=utf-8")
      assertEquals(get.body, RepositoryJson.serialize(repository))
      val contentLength = get.headers.firstValue("Content-Length").get

      val head = client.send(request(running.url, "HEAD"), HttpResponse.BodyHandlers.ofByteArray())
      assertEquals(head.statusCode, 200)
      assertEquals(head.headers.firstValue("Content-Type").get, "application/json; charset=utf-8")
      assertEquals(head.headers.firstValue("Content-Length").get, contentLength)
      assertEquals(head.body.toVector, Vector.empty)

      val post = client.send(request(running.url, "POST"), HttpResponse.BodyHandlers.discarding())
      assertEquals(post.statusCode, 405)
      assertEquals(post.headers.firstValue("Allow").get, "GET, HEAD")

      val query = client.send(request(running.url + "?not-exact"), HttpResponse.BodyHandlers.discarding())
      assertEquals(query.statusCode, 404)
    finally running.stop()

  test("remote fetch validates a single successful response and never follows redirects"):
    val requests = AtomicInteger(0)
    val server   = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val encoded  = RepositoryJson.serialize(repository)
    server.createContext(
      "/repository.json",
      new HttpHandler:
        override def handle(exchange: HttpExchange): Unit =
          requests.incrementAndGet()
          respond(exchange, 200, encoded)
    )
    server.createContext(
      "/redirect",
      new HttpHandler:
        override def handle(exchange: HttpExchange): Unit =
          requests.incrementAndGet()
          exchange.getResponseHeaders.set("Location", "/repository.json")
          exchange.sendResponseHeaders(302, -1)
          exchange.close()
    )
    server.createContext(
      "/malformed",
      new HttpHandler:
        override def handle(exchange: HttpExchange): Unit =
          requests.incrementAndGet()
          respond(exchange, 200, "not-json")
    )
    server.start()
    val base = s"http://127.0.0.1:${server.getAddress.getPort}"
    try
      assertEquals(Http.fetchRepository(base + "/repository.json"), Right(repository))
      assertEquals(requests.get, 1)

      assertEquals(Http.fetchRepository(base + "/redirect"), Left(SnapError("HTTP 302")))
      assertEquals(requests.get, 2)

      assert(Http.fetchRepository(base + "/malformed").left.exists(_.detail.contains("invalid JSON")))
      assertEquals(requests.get, 3)
    finally server.stop(0)
