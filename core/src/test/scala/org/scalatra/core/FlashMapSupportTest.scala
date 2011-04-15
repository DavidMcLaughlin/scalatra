package org.scalatra
package core

import org.scalatest.matchers.ShouldMatchers
import test.scalatest.ScalatraFunSuite
import servlet.{ScalatraFilter, ScalatraServlet}

class FlashMapSupportTestServlet extends ScalatraServlet with FlashMapSupport {
  post("/message") {
    flash("message") = "posted"
    flash.get("message") foreach { x => response.setHeader("message", x.toString) }
  }

  get("/message") {
    flash.get("message") foreach { x => response.setHeader("message", x.toString) }
  }

  post("/commit") {
    flash("message") = "oops"
    response.flushBuffer // commit response
  }
}

class FlashMapSupportTestFilter extends ScalatraFilter with FlashMapSupport {
  get("/filter") {
    flash.get("message") foreach { x => response.setHeader("message", x.toString) }
  }
}

class FlashMapSupportTest extends ScalatraFunSuite with ShouldMatchers {
  addFilter(classOf[FlashMapSupportTestFilter], "/*")
  addServlet(classOf[FlashMapSupportTestServlet], "/*")

  test("should sweep flash map at end of request") {
    session {
      post("/message") {
        header("message") should equal(null)
      }

      get("/message") {
        header("message") should equal("posted")
      }

      get("/message") {
        header("message") should equal(null)
      }
    }
  }

  test("should sweep flash map even if response has been committed") {
    session {
      post("/commit") {}

      get("/message") {
        header("message") should equal("oops")
      }
    }
  }

  test("flash map is session-scoped") {
    post("/message") {}

    get("/message") {
      header("message") should equal(null)
    }
  }

  test("messages should be available in outer filter when flash map supports are nested") {
    session {
      post("/message") {}
      get("/filter") {
        header("message") should equal ("posted")
      }
    }
  }
}

