package org.scalatra.core

import ScalatraKernel.MultiParams
import org.scalatra.ssgi.core.HttpMethod
import java.util.concurrent.{ConcurrentSkipListSet}
import collection.JavaConverters._
import annotation.tailrec
import org.scalatra.util.MultiMap
import util.matching.Regex
import collection.mutable

trait ScalatraAction {
  val action: () => Any

  def apply(params: MultiParams) = {
    try {
      Some(action())
    } catch {
      case e: ScalatraKernel#PassException => {
        None
      }
    }
  }
}
case class Action(method: HttpMethod, action: () => Any) extends ScalatraAction
case class BeforeFilter(action: () => Any) extends ScalatraAction
case class AfterFilter(action: () => Any) extends ScalatraAction

case class MatchedRoute[ActionType <: ScalatraAction](routeParams: MultiParams, actions: List[ActionType])

sealed trait LifeCycle
sealed trait Filtering extends LifeCycle
case object BeforeActions extends Filtering
case object Actions extends LifeCycle
case object AfterActions extends Filtering

trait RouteMatcher extends (String => Option[MultiParams])

trait ScalatraRouteImplicits {
  implicit def map2multimap(map: Map[String, Seq[String]]) = new MultiMap(map)
  implicit def fun2RouteMatcher(f: String => Option[MultiParams]) = new RouteMatcher { def apply(path: String) = f(path) }
  /**
   * Pluggable way to convert Strings into RouteMatchers.  By default, we
   * interpret them the same way Sinatra does.
   */
  protected implicit def string2RouteMatcher(path: String): RouteMatcher =
    SinatraPathPatternParser(path)

  /**
   * Path pattern is decoupled from requests.  This adapts the PathPattern to
   * a RouteMatcher by supplying the request path.
   */
  protected implicit def pathPatternParser2RouteMatcher(pattern: PathPattern): RouteMatcher =
    new RouteMatcher {
      def apply(path: String) = pattern(path)

      // By overriding toString, we can list the available routes in the
      // default notFound handler.
      override def toString() = pattern.regex.toString()
    }

  protected implicit def regex2RouteMatcher(regex: Regex): RouteMatcher = new RouteMatcher {
    def apply(path: String) = regex.findFirstMatchIn(path) map { _.subgroups match {
      case Nil => Map.empty
      case xs => Map("captures" -> xs)
    }}

    override def toString() = regex.toString()
  }

  protected implicit def booleanBlock2RouteMatcher(matcher: => Boolean): RouteMatcher =
    (path: String) => { if (matcher) Some(MultiMap()) else None }

}

object ScalatraRoute {
  def apply(routeMatchers: Iterable[RouteMatcher], action: ScalatraAction): ScalatraRoute = {
    val r = new ScalatraRoute(routeMatchers)
    r += action
  }
}

class ScalatraRoute(val routeMatchers: Iterable[RouteMatcher]) extends ScalatraRouteImplicits { // deliberately not a case class because this one is mutable

  private val _actions = new mutable.HashSet[ScalatraAction] with mutable.SynchronizedSet[ScalatraAction] {}
  private var _matchCache = Map.empty[String, MultiMap]
  def actions = _actions

  def isDefinedAt(matchers: Iterable[RouteMatcher]) = matchers.toList == routeMatchers.toList
  def isDefinedAt(path: String) = matchRoute(path).isDefined
  
  def apply(lifeCycle: LifeCycle, path: String) = lifeCycle match {
    case BeforeActions => {
      matchRoute(path) map { MatchedRoute(_, actions filter { _.isInstanceOf[BeforeFilter] } toList) }
    }
    case Actions => {
      matchRoute(path) map { MatchedRoute(_, actions filter { _.isInstanceOf[Action] } toList) }
    }
    case AfterActions => {
      matchRoute(path) map { MatchedRoute(_, actions filter { _.isInstanceOf[AfterFilter] } toList) }
    }
  }

  private def matchRoute(path: String) = {
    _matchCache.get(path) orElse {
      println("matching route: " + path)
      (Option(MultiMap()) /: routeMatchers) { (acc, rm) =>
        acc flatMap { x =>
          rm(path) map { y =>
            val m = MultiMap(x ++ y)
            _matchCache += path -> m
            m
          }
        }
      }
    }
  }

  def +=(action: ScalatraAction) = {
    _actions += action
    this
  }

  def -=(method: HttpMethod) = {
    val act = actions filterNot {
      case m: Action => m.method == method
      case _ => false
    }
    _actions.clear()
    _actions ++= act
  }

  override def equals(other: Any) = other match {
    case r: ScalatraRoute => r.routeMatchers == routeMatchers && r.actions == actions
    case _ => false
  }

  override def hashCode() = 41 * ( 41 + routeMatchers.toList.hashCode ) + actions.hashCode

  override def toString =
    "ScalatraRoute(matchers=[%s], actionCount=[%s], actions=[%s])".format(
      routeMatchers.mkString(", "), actions.size, actions.mkString(", "))


}

class RouteRegistry {

  private[scalatra] val routes = new mutable.HashSet[ScalatraRoute] with mutable.SynchronizedSet[ScalatraRoute] {}

  def +=(kv: (Iterable[RouteMatcher], ScalatraAction)) = {
    val (routeMatchers, action) = kv
    routes find { _.isDefinedAt(routeMatchers) } map { _ += action } getOrElse {
      val r = ScalatraRoute(routeMatchers, action)
      routes += r
      r
    }
  }

  def -=(route: ScalatraRoute) = routes -= route

  def apply(lifeCycleStage: LifeCycle, path: String) = {
    routes filter { _.isDefinedAt(path) } flatMap { _.apply(lifeCycleStage, path) } toStream
  }

}