package org.scalatra
package core

import org.scalatest.matchers.ShouldMatchers
import test.scalatest.ScalatraFunSuite

object DefaultRouteTest {
  val existingRoute = "/existing-route"
  val nonExistentRoute = "/no-such-route"
}

class DefaultRouteTestServlet extends ScalatraServlet {
  import DefaultRouteTest._

  get(existingRoute) {
    println(routes.routes.mkString("[", ", ", "]"))
    "get"
  }

  post(existingRoute) {
    println(routes.routes.mkString("[", ", ", "]"))
    "post"
  }

  put(existingRoute) {
    println(routes.routes.mkString("[", ", ", "]"))
    "put"
  }

  delete(existingRoute) {
    println(routes.routes.mkString("[", ", ", "]"))
    "delete"
  }

  options(existingRoute) {
    println(routes.routes.mkString("[", ", ", "]"))
    "options"
  }
}

class DefaultRouteTest extends ScalatraFunSuite with ShouldMatchers {
  import DefaultRouteTest._

  addServlet(classOf[DefaultRouteTestServlet], "/*")

  test("GET request to non-existent route should return 404") {
    get(nonExistentRoute) {
      status should equal (404)
    }
  }

  test("GET request to existing route should return 200") {
    get(existingRoute) {
      status should equal (200)
    }
  }

  test("POST request to non-existent route should return 404") {
    post(nonExistentRoute) {
      status should equal (404)
    }
  }

  test("POST request to existing route should return 200") {
    post(existingRoute) {
      status should equal (200)
    }
  }

  test("PUT request to non-existent route should return 404") {
    put(nonExistentRoute) {
      status should equal (404)
    }
  }

  test("PUT request to existing route should return 200") {
    put(existingRoute) {
      status should equal (200)
    }
  }

  test("DELETE request to non-existent route should return 404") {
    delete(nonExistentRoute) {
      status should equal (404)
    }
  }

  test("DELETE request to existing route should return 200") {
    delete(existingRoute) {
      status should equal (200)
    }
  }

  test("OPTIONS request to non-existent route should return 404") {
    options(nonExistentRoute) {
      status should equal (404)
    }
  }

  test("OPTIONS request to existing route should return 200") {
    options(existingRoute) {
      status should equal (200)
    }
  }
}
