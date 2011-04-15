package org.scalatra
package example

import core.CookieSupport
import servlet.ScalatraServlet

class CookiesExample extends ScalatraServlet with CookieSupport {
  get("/cookies-example") {
    val previous = cookies.get("counter") match {
      case Some(v) =>  v.toInt
      case None    => 0
    }
    cookies.update("counter", (previous+1).toString)
    <p>
      Hi, you have been on this page {previous} times already
    </p>
  }
}
