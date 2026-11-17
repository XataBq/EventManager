package ru.eventpass.data.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** Bounded ZIP/CRC/OOXML validation before any byte reaches a user document. No workbook model in the UI. */
internal object XlsxFormat {
    fun check(bytes:ByteArray,cancel:()->Unit) {
        require(bytes.size in 22..33_554_432)
        fun u16(at:Int):Int {require(at>=0 && at+2<=bytes.size);return (bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8)}
        fun u32(at:Int):Long = u16(at).toLong() or (u16(at+2).toLong() shl 16)
        require(u32(0)==0x04034b50L)
        val end=(bytes.size-22 downTo maxOf(0,bytes.size-65_557)).firstOrNull {u32(it)==0x06054b50L && it+22+u16(it+20)==bytes.size}
            ?: throw IllegalArgumentException("Missing ZIP directory")
        require(u16(end+4)==0 && u16(end+6)==0)
        val count=u16(end+10);require(count in 1..10_000 && count==u16(end+8))
        val offset=u32(end+16);val size=u32(end+12)
        require(offset+size==end.toLong()) // ZIP64/multipart archives are outside this bounded format.
        var directory=offset.toInt()
        repeat(count) {
            cancel();require(u32(directory)==0x02014b50L)
            directory+=46+u16(directory+28)+u16(directory+30)+u16(directory+32)
            require(directory<=end)
        }
        require(directory==end)
        val errors=object:DefaultHandler() {
            override fun error(ex:org.xml.sax.SAXParseException) {throw ex}
            override fun fatalError(ex:org.xml.sax.SAXParseException) {throw ex}
        }
        val parser=SAXParserFactory.newInstance().apply {
            isNamespaceAware=true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        }.newSAXParser().xmlReader.apply {contentHandler=DefaultHandler();errorHandler=errors}
        val names=mutableSetOf<String>();val metadata=mutableMapOf<String,ByteArray>();var total=0L
        ZipInputStream(ByteArrayInputStream(bytes)).use {zip->
            while(true) {
                val entry=zip.nextEntry ?: break;cancel()
                require(names.add(entry.name) && names.size<=10_000 && !entry.name.startsWith('/') && '\\' !in entry.name && entry.name.split('/').none {it==".."})
                var part=0L
                val bounded=object:FilterInputStream(zip) {
                    private fun accepted(n:Int):Int {if(n>0) {part+=n;total+=n;require(part<=67_108_864 && total<=134_217_728);cancel()};return n}
                    override fun read():Int {val n=zip.read();if(n>=0) accepted(1);return n}
                    override fun read(b:ByteArray,off:Int,len:Int)=accepted(zip.read(b,off,len))
                    override fun close() {} // The entry parser never owns the archive stream.
                }
                if(entry.name in setOf("[Content_Types].xml","xl/workbook.xml")) {
                    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {val n=bounded.read(buffer);if(n<0) break;require(output.size()+n<=2_097_152);output.write(buffer,0,n)}
                    metadata[entry.name]=output.toByteArray()
                } else if(!entry.isDirectory && (entry.name.endsWith(".xml") || entry.name.endsWith(".rels"))) {
                    parser.parse(InputSource(bounded))
                }
                val buffer=ByteArray(8192);while(bounded.read(buffer)>=0) {cancel()}
                zip.closeEntry() // Includes CRC/truncation validation.
            }
        }
        require(names.size==count && "_rels/.rels" in names && "xl/_rels/workbook.xml.rels" in names && names.any {it.startsWith("xl/worksheets/") && it.endsWith(".xml")})
        val factory=DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true;isExpandEntityReferences=false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        }
        val types=factory.newDocumentBuilder().apply {setErrorHandler(errors)}.parse(ByteArrayInputStream(requireNotNull(metadata["[Content_Types].xml"])))
        require(types.documentElement.localName=="Types" && types.documentElement.namespaceURI=="http://schemas.openxmlformats.org/package/2006/content-types")
        val overrides=types.getElementsByTagNameNS(types.documentElement.namespaceURI,"Override")
        require((0 until overrides.length).any {i->val element=overrides.item(i) as org.w3c.dom.Element
            element.getAttribute("PartName")=="/xl/workbook.xml" && element.getAttribute("ContentType")=="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"})
        val workbook=factory.newDocumentBuilder().apply {setErrorHandler(errors)}.parse(ByteArrayInputStream(requireNotNull(metadata["xl/workbook.xml"])))
        require(workbook.documentElement.localName=="workbook" && workbook.documentElement.namespaceURI=="http://schemas.openxmlformats.org/spreadsheetml/2006/main")
        cancel()
    }
}
