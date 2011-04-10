package org.scalatra

class CookiesExample extends servlet.ScalatraServlet with core.CookieSupport {
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
