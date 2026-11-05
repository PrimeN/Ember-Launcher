package app.ember.tv.player

import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/** Android's XML implementation does not support every desktop Xerces feature.
 * Mandatory input/structure checks and a rejecting resolver remain effective on both.
 */
internal object SafePlayerXml {
    fun parse(text:String):org.w3c.dom.Document {
        require(text.length<=4*1024*1024 && '\u0000' !in text && !text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true)) {"Unsupported XML declaration."}
        val guard=object:DefaultHandler() {
            var depth=0;var nodes=0
            override fun startElement(uri:String?,localName:String?,qName:String?,attrs:org.xml.sax.Attributes?) {check(++depth<=64 && ++nodes<=50000) {"Media XML is too complex."}}
            override fun endElement(uri:String?,localName:String?,qName:String?){depth--}
            override fun resolveEntity(publicId:String?,systemId:String?):InputSource=throw SAXException("External XML references are disabled.")
            override fun error(e:SAXParseException)=throw SAXException("Invalid media XML.")
            override fun fatalError(e:SAXParseException)=throw SAXException("Invalid media XML.")
        }
        SAXParserFactory.newInstance().apply {isNamespaceAware=true}.newSAXParser().parse(InputSource(StringReader(text)),guard)
        val builder=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true;isExpandEntityReferences=false}.newDocumentBuilder()
        builder.setEntityResolver(guard);builder.setErrorHandler(guard)
        return builder.parse(InputSource(StringReader(text)))
    }
}
