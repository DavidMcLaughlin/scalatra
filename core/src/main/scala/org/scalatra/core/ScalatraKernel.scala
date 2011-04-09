package org.scalatra
package core

import javax.servlet._
import javax.servlet.http._
import scala.util.DynamicVariable
import scala.collection.JavaConversions._
import scala.xml.NodeSeq
import ssgi.core._
import util.io.zeroCopy
import java.io.{File, FileInputStream}
import scala.annotation.tailrec
import util.{MultiMap, MapWithIndifferentAccess, MultiMapHeadView, using}

object ScalatraKernel
{
  type MultiParams = MultiMap

  @deprecated("Use HttpMethods.methods")
    val httpMethods = HttpMethod.methods map { _.toString }

    @deprecated("Use HttpMethods.methods filter { !_.isSafe }")
    val writeMethods = HttpMethod.methods filter { !_.isSafe } map { _.toString }

    @deprecated("Use CsrfTokenSupport.DefaultKey")
    val csrfKey = CsrfTokenSupport.DefaultKey

    val EnvironmentKey = "org.scalatra.environment"
  }
  import ScalatraKernel._

  /**
   * ScalatraKernel provides the DSL for building Scalatra applications.
   *
   * At it's core a type mixing in ScalatraKernel is a registry of possible actions,
   * every request is dispatched to the first route matching.
   *
   * The [[org.scalatra.ScalatraKernel#get]], [[org.scalatra.ScalatraKernel#post]],
   * [[org.scalatra.ScalatraKernel#put]] and [[org.scalatra.ScalatraKernel#delete]]
   * methods register a new action to a route for a given HTTP method, possibly
   * overwriting a previous one. This trait is thread safe.
   */
  trait ScalatraKernel extends core.Handler with core.Initializable with core.Dsl // help IDEA a little
  {

    def contentType = response.getContentType
    def contentType_=(value: String) {
      response.setContentType(value)
    }

    protected val defaultCharacterEncoding = "UTF-8"
    protected val _response   = new DynamicVariable[HttpServletResponse](null)
    protected val _request    = new DynamicVariable[HttpServletRequest](null)

    protected implicit def requestWrapper(r: HttpServletRequest) = new RichRequest(r)
    protected implicit def sessionWrapper(s: HttpSession) = new RichSession(s)
    protected implicit def servletContextWrapper(sc: ServletContext) = new RichServletContext(sc)

    private def runAction(actionRoutes: List[MatchedRoute[ScalatraAction]], realMultiParams: Map[String, scala.Seq[String]]): (MultiParams, Any) = {
      var actionParams = new MultiParams()
      val ares = (actionRoutes flatMap {
        r =>
          _multiParams.withValue(realMultiParams ++ r.routeParams) {
            val acts = r.actions.map(_.asInstanceOf[Action])
            acts.filter(_.method == effectiveMethod).foldLeft(None.asInstanceOf[Option[Any]]) {
              (acc, rr) =>
                if (acc.isEmpty) {
                  actionParams = r.routeParams // keeping these around so subsequent actions also have access to the goodies
                  val res = rr(multiParams)
                  res
                } else acc
            }
          }
      } headOption) orElse {
        val alt = actionRoutes.flatMap(_.actions.map(_.asInstanceOf[Action]))
            .filterNot(_.method == effectiveMethod)
            .map(_.method).toList
        if (!alt.isEmpty) {
          methodNotAllowed(alt)
        }
        None
      } getOrElse doNotFound()
      (actionParams, ares)
    }

    def handle(request: HttpServletRequest, response: HttpServletResponse) {
      // As default, the servlet tries to decode params with ISO_8859-1.
      // It causes an EOFException if params are actually encoded with the other code (such as UTF-8)
      if (request.getCharacterEncoding == null)
        request.setCharacterEncoding(defaultCharacterEncoding)

      val realMultiParams = request.getParameterMap.asInstanceOf[java.util.Map[String,Array[String]]].toMap
        .transform { (k, v) => v: Seq[String] }

      response.setCharacterEncoding(defaultCharacterEncoding)

      _request.withValue(request) {
        _response.withValue(response) {
          _multiParams.withValue(Map() ++ realMultiParams) {
            var actionParams: MultiParams = MultiMap()
            val result = try {
              val actionRoutes = routes(Actions, requestPath)
              val notFound = actionRoutes.isEmpty
              // TODO: Should before filters always run or only when an action matches?
              runFilters(BeforeActions, multiParams)
              if(notFound) {
                doNotFound()
              } else {
                val (ap, res) = runAction(actionRoutes, realMultiParams)
                actionParams = ap
                res
              }
            }
            catch {
              case e => {
                _multiParams.withValue(multiParams ++ actionParams) { handleError(e) }
              }
            }
            finally {
              // TODO: should after filters always run or only when there was a match?
              // TODO: should after fitlers run when an error occurred?
              runFilters(AfterActions, multiParams ++ actionParams)
            }
            renderResponse(result)
          }
        }
      }
    }

    private def runFilters(lifeCycle: Filtering, pars: => MultiParams) {
      routes(lifeCycle, requestPath).reverse foreach { r =>
        _multiParams.withValue(pars ++ r.routeParams) {
          r.actions foreach { _(multiParams) }
        }
      }
    }

    protected def effectiveMethod: HttpMethod =
      HttpMethod(request.getMethod) match {
        case Head => Get
        case x => x
      }

    def requestPath: String

    def methodNotAllowed(alternatives: List[HttpMethod]) =
      halt(405, alternatives.mkString("Only the methods: [", ",", "] are allowed"))

    protected var doNotFound: () => Any
    def notFound(fun: => Any) = doNotFound = { () => fun }

    protected def handleError(e: Throwable): Any = {
      (renderError orElse defaultRenderError).apply(e)
    }

    protected def renderError : PartialFunction[Throwable, Any] = defaultRenderError

    protected final def defaultRenderError : PartialFunction[Throwable, Any] = {
      case HaltException(Some(code), Some(msg)) => response.sendError(code, msg)
      case HaltException(Some(code), None) => response.sendError(code)
      case HaltException(None, _) =>
      case e => {
        status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        _caughtThrowable.withValue(e) { errorHandler() }
      }
    }

    protected var errorHandler: () => Any = { () => throw caughtThrowable }
    def error(fun: => Any) = errorHandler = { () => fun }

    private val _caughtThrowable = new DynamicVariable[Throwable](null)
    protected def caughtThrowable = _caughtThrowable.value

    protected def renderResponse(actionResult: Any) {
      if (contentType == null)
        contentType = inferContentType(actionResult)
      renderResponseBody(actionResult)
    }

    type ContentTypeInferrer = PartialFunction[Any, String]

    protected def defaultContentTypeInfer: ContentTypeInferrer = {
      case _: NodeSeq => "text/html"
      case _: Array[Byte] => "application/octet-stream"
      case _ => "text/plain"
    }
    protected def contentTypeInfer: ContentTypeInferrer = defaultContentTypeInfer

    protected def inferContentType(actionResult: Any): String =
      (contentTypeInfer orElse defaultContentTypeInfer).apply(actionResult)

    protected def renderResponseBody(actionResult: Any) {
      @tailrec def loop(ar: Any): Any = ar match {
        case r: Unit => r
        case a => loop((renderPipeline orElse defaultRenderResponse) apply a)
      }
      loop(actionResult)
    }

    protected def renderPipeline: PartialFunction[Any, Any] = defaultRenderResponse

    protected final def defaultRenderResponse: PartialFunction[Any, Any] = {
      case bytes: Array[Byte] =>
        response.getOutputStream.write(bytes)
      case file: File =>
        using(new FileInputStream(file)) { in => zeroCopy(in, response.getOutputStream) }
      case _: Unit =>
      // If an action returns Unit, it assumes responsibility for the response
      case x: Any  =>
        response.getWriter.print(x.toString)
    }

    protected[scalatra] val _multiParams = new DynamicVariable[MultiMap](new MultiMap)
    protected def multiParams: MultiParams = (MultiMap(_multiParams.value)).withDefaultValue(Seq.empty)
    /*
     * Assumes that there is never a null or empty value in multiParams.  The servlet container won't put them
     * in request.getParameters, and we shouldn't either.
     */
    protected val _params = new MultiMapHeadView[String, String] with MapWithIndifferentAccess[String] {
      protected def multiMap = multiParams
    }
    def params = _params

    def redirect(uri: String) = (_response value) sendRedirect uri
    implicit def request = _request value
    implicit def response = _response value
    def session = request.getSession
    def sessionOption = request.getSession(false) match {
      case s: HttpSession => Some(s)
      case null => None
    }
    def status(code: Int) = (_response value) setStatus code

    def halt(code: Int, msg: String) = throw new HaltException(Some(code), Some(msg))
    def halt(code: Int) = throw new HaltException(Some(code), None)
    def halt() = throw new HaltException(None, None)
    protected[scalatra] case class HaltException(code: Option[Int], msg: Option[String]) extends RuntimeException

    def pass() = throw new PassException
    protected[scalatra] class PassException extends RuntimeException

    private var config: Config = _
    def initialize(config: Config) = this.config = config

    def initParameter(name: String): Option[String] = config match {
      case config: ServletConfig => Option(config.getInitParameter(name))
      case config: FilterConfig => Option(config.getInitParameter(name))
      case _ => None
    }

    def environment: String = System.getProperty(EnvironmentKey, initParameter(EnvironmentKey).getOrElse("development"))
    def isDevelopmentMode = environment.toLowerCase.startsWith("dev")

    /**
     * Uniquely identifies this ScalatraKernel inside the webapp.
     */
    def kernelName: String
  }
