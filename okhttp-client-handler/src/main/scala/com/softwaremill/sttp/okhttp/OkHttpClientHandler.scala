package com.softwaremill.sttp.okhttp

import java.io.IOException
import java.nio.charset.Charset

import com.softwaremill.sttp._
import com.softwaremill.sttp.model._
import okhttp3.internal.http.HttpMethod
import okhttp3.{
  Call,
  Callback,
  MediaType,
  OkHttpClient,
  Request => OkHttpRequest,
  RequestBody => OkHttpRequestBody,
  Response => OkHttpResponse
}
import okio.{BufferedSink, Okio}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.higherKinds

abstract class OkHttpClientHandler[R[_], S](client: OkHttpClient)
    extends SttpHandler[R, S] {
  private[okhttp] def convertRequest[T](request: Request[T, S]): OkHttpRequest = {
    val builder = new OkHttpRequest.Builder()
      .url(request.uri.toString)

    val body = setBody(request.body)
    builder.method(request.method.m, body.getOrElse {
      if (HttpMethod.requiresRequestBody(request.method.m))
        OkHttpRequestBody.create(null, "")
      else null
    })

    //OkHttp support automatic gzip compression
    request.headers
      .filter(_._1.equalsIgnoreCase(AcceptEncodingHeader) == false)
      .foreach {
        case (name, value) => builder.addHeader(name, value)
      }

    builder.build()
  }

  private def setBody(requestBody: RequestBody[S]): Option[OkHttpRequestBody] = {
    requestBody match {
      case NoBody => None
      case StringBody(b, encoding) =>
        Some(OkHttpRequestBody.create(MediaType.parse(encoding), b))
      case ByteArrayBody(b)  => Some(OkHttpRequestBody.create(null, b))
      case ByteBufferBody(b) => Some(OkHttpRequestBody.create(null, b.array()))
      case InputStreamBody(b) =>
        Some(new OkHttpRequestBody() {
          override def writeTo(sink: BufferedSink): Unit =
            sink.writeAll(Okio.source(b))
          override def contentType(): MediaType = null
        })
      case PathBody(b)            => Some(OkHttpRequestBody.create(null, b.toFile))
      case SerializableBody(f, t) => setBody(f(t))
      case StreamBody(s)          => None
    }
  }

  private[okhttp] def readResponse[T](
      res: OkHttpResponse,
      responseAs: ResponseAs[T, S]): R[Response[T]] = {
    val body = readResponseBody(res, responseAs)

    val headers = res
      .headers()
      .names()
      .asScala
      .flatMap(name => res.headers().values(name).asScala.map((name, _)))

    responseMonad.map(body, Response(_: T, res.code(), headers.toList))
  }

  private def readResponseBody[T](res: OkHttpResponse,
                                  responseAs: ResponseAs[T, S]): R[T] = {
    responseAs match {
      case IgnoreResponse => responseMonad.unit(res.body().close())
      case ResponseAsString(encoding) =>
        responseMonad.unit(
          res.body().source().readString(Charset.forName(encoding)))
      case ResponseAsByteArray => responseMonad.unit(res.body().bytes())
      case MappedResponseAs(raw, g) =>
        responseMonad.map(readResponseBody(res, raw), g)
      case ResponseAsStream() =>
        responseMonad.error(
          new IllegalStateException("Streaming isn't supported"))
    }
  }
}

class OkHttpSyncClientHandler private (client: OkHttpClient)
    extends OkHttpClientHandler[Id, Nothing](client) {
  override def send[T](r: Request[T, Nothing]): Response[T] = {
    val request = convertRequest(r)
    val response = client.newCall(request).execute()
    readResponse(response, r.responseAs)
  }

  override def responseMonad: MonadError[Id] = IdMonad
}

object OkHttpSyncClientHandler {
  def apply(okhttpClient: OkHttpClient = new OkHttpClient())
    : SttpHandler[Id, Nothing] =
    new OkHttpSyncClientHandler(okhttpClient)
}

class OkHttpFutureClientHandler private (client: OkHttpClient)(
    implicit ec: ExecutionContext)
    extends OkHttpClientHandler[Future, Nothing](client) {

  override def send[T](r: Request[T, Nothing]): Future[Response[T]] = {
    val request = convertRequest(r)
    val promise = Promise[Future[Response[T]]]()

    client
      .newCall(request)
      .enqueue(new Callback {
        override def onFailure(call: Call, e: IOException): Unit =
          promise.failure(e)

        override def onResponse(call: Call, response: OkHttpResponse): Unit =
          promise.success(readResponse(response, r.responseAs))
      })

    responseMonad.flatten(promise.future)
  }

  override def responseMonad: MonadError[Future] = new FutureMonad
}

object OkHttpFutureClientHandler {
  def apply(okhttpClient: OkHttpClient = new OkHttpClient())(
      implicit ec: ExecutionContext = ExecutionContext.Implicits.global)
    : SttpHandler[Future, Nothing] =
    new OkHttpFutureClientHandler(okhttpClient)
}
