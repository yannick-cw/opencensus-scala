package com.github.sebruck.opencensus.http4s

import cats._
import cats.data.{Kleisli, OptionT}
import cats.effect.Effect
import cats.implicits._
import com.github.sebruck.opencensus.Tracing
import com.github.sebruck.opencensus.http.StatusTranslator
import com.github.sebruck.opencensus.http.propagation.Propagation
import com.github.sebruck.opencensus.http4s.TracingService.{
  SpanRequest,
  TracingService
}
import com.github.sebruck.opencensus.http4s.propagation.B3FormatPropagation
import io.opencensus.trace.{Span, Status}
import org.http4s.{Header, HttpService, Request, Response}

trait TracingMiddleware {
  protected def tracing: Tracing
  protected def propagation[F[_]]: Propagation[Header, Request[F]]

  /**
    * Transforms a `TracingService` to a `HttpService` to be ready to run by a server.
    * Starts a new span and sets a parent context if the request contains valid headers in the b3 format.
    * The span is ended when the request completes or fails with a status code which is suitable
    * to the http response code.
    * @return HttpService[F]
    */
  def apply[F[_]: Effect](tracingService: TracingService[F]): HttpService[F] =
    Kleisli { req =>
      val span = buildSpan(req)
      OptionT(
        tracingService(SpanRequest(span, req))
          .map(recordSuccess(span))
          .value
          .adaptError {
            case e =>
              recordException(span)
              e
          })
    }

  /**
    * Adds tracing to a `HttpService[F]`, does not pass the `span` to the service itself.
    * Use `TracingMiddleware.apply` for that.
    * Starts a new span and sets a parent context if the request contains valid headers in the b3 format.
    * The span is ended when the request completes or fails with a status code which is suitable
    * to the http response code.
    * @return HttpService[F]
    */
  def withoutSpan[F[_]: Effect](service: HttpService[F]): HttpService[F] =
    apply(service.local[SpanRequest[F]](spanReq => spanReq.req))

  private def buildSpan[F[_]](req: Request[F]): Span = {
    val name = req.uri.path.toString
    val span = propagation[F]
      .extractContext(req)
      .fold(
        _ => tracing.startSpan(name),
        tracing.startSpanWithRemoteParent(name, _)
      )
    HttpAttributes.setAttributesForRequest(span, req)
    span
  }

  private def recordSuccess[F[_]](span: Span)(
      response: Response[F]): Response[F] = {
    HttpAttributes.setAttributesForResponse(span, response)
    tracing.endSpan(span, StatusTranslator.translate(response.status.code))
    response
  }

  private def recordException(span: Span): Unit =
    tracing.endSpan(span, Status.INTERNAL)
}

object TracingMiddleware extends TracingMiddleware {
  override protected def tracing: Tracing = Tracing
  override protected def propagation[F[_]]: Propagation[Header, Request[F]] =
    new B3FormatPropagation[F] {}
}

object TracingService {
  case class SpanRequest[F[_]](span: Span, req: Request[F])

  type TracingService[F[_]] =
    Kleisli[OptionT[F, ?], SpanRequest[F], Response[F]]

  /**
    * Creates a `TracingService` from a partial function over `SpanRequest[F] => F[Response[F]]`
    * works similar to `org.http4s.HttpService`, but needs to extract the `span` from the
    * `SpanRequest` e.g.:
    * `TracingService[IO] {
    *  case GET -> Root / "path" withSpan span => Ok()
    * }`
    * @return TracingService[F]
    */
  def apply[F[_]](pf: PartialFunction[SpanRequest[F], F[Response[F]]])(
      implicit F: Applicative[F]): TracingService[F] =
    Kleisli(
      req =>
        pf.andThen(OptionT.liftF(_))
          .applyOrElse(req, (_: SpanRequest[F]) => OptionT.none))

  /**
    * Used to extract the `span` from the `SpanRequest` e.g.:
    * `case GET -> Root / "path" withSpan span => Ok()`
    */
  object withSpan {
    def unapply[F[_], A](sr: SpanRequest[F]): Option[(Request[F], Span)] =
      Some(sr.req -> sr.span)
  }
}
