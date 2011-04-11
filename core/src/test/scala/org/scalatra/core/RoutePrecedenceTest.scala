package org.scalatra
package core

import org.scalatest.matchers.ShouldMatchers
import test.scalatest.ScalatraFunSuite

class RoutePrecedenceTestBaseServlet extends servlet.ScalatraServlet {
  get("/override-route") {
    "base"
  }
}

class RoutePrecedenceTestChildServlet extends RoutePrecedenceTestBaseServlet {
  get("/override-route") {
    "child"
  }



  get("/hide-route") {
    "hidden by earlier route"
  }

  get("/hide-route") {
    "visible"
  }


  get("/pass") {
    response.getWriter.write("1")
    pass()
    response.getWriter.write("2")
  }

  get("/pass") {
    response.getWriter.write("3")
  }

  get("/pass-to-not-found") {
    response.getWriter.write("a")
    pass()
    response.getWriter.write("b")
  }

  get("/do-not-pass") {
    response.getWriter.write("1")
  }

  get("/do-not-pass") {
    response.getWriter.write("2")
  }

  notFound {
    response.getWriter.write("c")
  }
}

class RoutePrecedenceTest extends ScalatraFunSuite with ShouldMatchers {
  addServlet(classOf[RoutePrecedenceTestChildServlet], "/*")

  test("Routes in child should override routes in base") {
    get("/override-route") {
      body should equal ("child")
    }
  }

  test("Routes declared later in the same class take precedence") {
    /*
     * This is the opposite of Sinatra, where the earlier route wins.  But to do otherwise, while also letting child
     * classes override base classes' routes, proves to be difficult in an internal Scala DSL.  Sorry, Sinatra users.
     */
    get("/hide-route") {
      body should equal ("visible")
    }
  }

  test("pass immediately passes to next matching route") {
    get("/pass") {
      body should equal ("13")
    }
  }

  test("pass invokes notFound action if no more matching routes") {
    get("/pass-to-not-found") {
      body should equal ("ac")
    }
  }

  test("does not keep executing routes without pass") {
    get("/do-not-pass") {
      body should equal ("1")
    }
  }
}