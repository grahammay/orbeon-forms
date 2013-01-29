/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.analysis.model


import org.orbeon.oxf.processor.ProcessorImpl
import org.orbeon.oxf.util.{XPathCache, NetUtils}
import org.orbeon.oxf.xforms._
import analysis.{StaticStateContext, SimpleElementAnalysis, ElementAnalysis}
import xbl.Scope
import org.orbeon.oxf.util.Connection.Credentials
import XFormsConstants._
import org.dom4j.{Document, QName, Element}
import org.orbeon.oxf.xml.dom4j.{Dom4jUtils, ExtendedLocationData}
import org.orbeon.oxf.common.{ValidationException, Version}
import org.orbeon.oxf.xml.{TransformerUtils, ContentHandlerHelper, Dom4j}
import org.orbeon.oxf.util.ScalaUtils.stringOptionToSet
import org.orbeon.saxon.om.DocumentInfo
import scala.collection.JavaConverters._
import org.orbeon.saxon.dom4j.{DocumentWrapper, TypedDocumentWrapper}

/**
 * Static analysis of an XForms instance.
 */
class Instance(staticStateContext: StaticStateContext, element: Element, parent: Option[ElementAnalysis], preceding: Option[ElementAnalysis], scope: Scope)
        extends SimpleElementAnalysis(staticStateContext, element, parent, preceding, scope) {

    import Instance._

    val readonly = element.attributeValue(XXFORMS_READONLY_ATTRIBUTE_QNAME) == "true"
    val cache = Version.instance.isPEFeatureEnabled(element.attributeValue(XXFORMS_CACHE_QNAME) == "true", "cached XForms instance")
    val timeToLive = Instance.timeToLiveOrDefault(element)
    val handleXInclude = false

    val validation = element.attributeValue(XXFORMS_VALIDATION_QNAME)
    def exposeXPathTypes = part.isExposeXPathTypes // NOTE: per part

    def isLaxValidation    = (validation eq null) || validation == "lax"
    def isStrictValidation = validation == "strict"
    def isSchemaValidation = isLaxValidation || isStrictValidation

    val credentialsOrNull = {
        // NOTE: AVTs not supported because XPath expressions in those could access instances that haven't been loaded
        def username = element.attributeValue(XXFORMS_USERNAME_QNAME)
        def password = element.attributeValue(XXFORMS_PASSWORD_QNAME)
        def preemptiveAuthentication = element.attributeValue(XXFORMS_PREEMPTIVE_AUTHENTICATION_QNAME)
        def domain = element.attributeValue(XXFORMS_DOMAIN_QNAME)

        Option(username) map (Credentials(_, password, preemptiveAuthentication, domain)) orNull
    }

    val excludeResultPrefixes = stringOptionToSet(Option(element.attributeValue(XXFORMS_EXCLUDE_RESULT_PREFIXES)))

    // Inline root element if any
    private val root = Dom4j.elements(element) headOption
    private def hasInlineContent = root.isDefined

    // Create inline instance document if any
    // NOTE: Result can't be shared. In the future we could share the extracted document and copy as needed.
    def inlineContent = root map (extractDocument(_, excludeResultPrefixes, readonly, exposeXPathTypes))

    // Don't allow more than one child element
    if (Dom4j.elements(element).size > 1)
        throw new ValidationException("xf:instance must contain at most one child element", extendedLocationData)

    private def getAttributeEncode(qName: QName) = Option(element.attributeValue(qName)) map (att ⇒ NetUtils.encodeHRRI(att.trim, true))

    private def src = getAttributeEncode(SRC_QNAME)
    private def resource = getAttributeEncode(RESOURCE_QNAME)

    // @src always wins, @resource always loses
    val useInlineContent = ! src.isDefined && hasInlineContent
    val useExternalContent = src.isDefined || ! hasInlineContent && resource.isDefined

    val (instanceSource, dependencyURL) =
        (if (useInlineContent) None else src orElse resource) match {
            case someSource @ Some(source) if ProcessorImpl.isProcessorOutputScheme(source) ⇒
                someSource → None // input:* doesn't add a URL dependency, but is handled by the pipeline engine
            case someSource @ Some(_) ⇒
                someSource → someSource
            case _ ⇒
                None → None
        }

    // Don't allow a blank src attribute
    if (useExternalContent && instanceSource == Some(""))
        throw new ValidationException("xf:instance must not specify a blank URL", extendedLocationData)

    private def extendedLocationData = new ExtendedLocationData(locationData, "processing XForms instance", element, "id", staticId)

    // For now we don't want to see instances printed as controls in unit tests
    override def toXML(helper: ContentHandlerHelper, attributes: List[String])(content: ⇒ Unit) = ()
}

object Instance {
    def timeToLiveOrDefault(element: Element) = {
        val timeToLiveValue = element.attributeValue(XXFORMS_TIME_TO_LIVE_QNAME)
        Option(timeToLiveValue) map (_.toLong) getOrElse -1L
    }

    // Extract the document starting at the given root element
    // This always creates a copy of the original sub-tree
    //
    // @readonly         if true, the document returned is a compact TinyTree, otherwise a DocumentWrapper
    // @exposeXPathTypes if true, use a TypedDocumentWrapper
    def extractDocument(element: Element, excludeResultPrefixes: Set[String], readonly: Boolean, exposeXPathTypes: Boolean): DocumentInfo = {

        // Extract a document and adjust namespaces if requested
        // NOTE: Should implement exactly as per XSLT 2.0
        // NOTE: Should implement namespace fixup, the code below can break serialization
        def extractDocument =
            excludeResultPrefixes match {
                case prefixes if prefixes("#all") ⇒
                    // Special #all
                    Dom4jUtils.createDocumentCopyElement(element)
                case prefixes if prefixes.nonEmpty ⇒
                    // List of prefixes
                    Dom4jUtils.createDocumentCopyParentNamespaces(element, prefixes.asJava)
                case _ ⇒
                    // No exclusion
                    Dom4jUtils.createDocumentCopyParentNamespaces(element)
            }

        val document = extractDocument
        if (readonly)
            TransformerUtils.dom4jToTinyTree(XPathCache.getGlobalConfiguration, document, false)
        else
            wrapDocument(document, exposeXPathTypes)
    }

    def wrapDocument(document: Document, exposeXPathTypes: Boolean) =
        if (exposeXPathTypes)
            new TypedDocumentWrapper(Dom4jUtils.normalizeTextNodes(document).asInstanceOf[Document], null, XPathCache.getGlobalConfiguration)
        else
            new DocumentWrapper(Dom4jUtils.normalizeTextNodes(document).asInstanceOf[Document], null, XPathCache.getGlobalConfiguration)
}