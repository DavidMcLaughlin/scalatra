package org.scalatra.ssgi
package object servlet {
  /**
   * Structural type for the various Servlet API objects that have attributes.  These include ServletContext,
   * HttpSession, and ServletRequest.
   */
  type Attributes = {
    def getAttribute(name: String): AnyRef
    def getAttributeNames(): java.util.Enumeration[_]
    def setAttribute(name: String, value: AnyRef): Unit
    def removeAttribute(name: String): Unit
  }
}
