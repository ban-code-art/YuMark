package com.yumark.app.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WebDavXmlTest {

    private val multistatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/YuMark/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/YuMark/Note.md</d:href>
            <d:propstat><d:prop>
              <d:getetag>"abc123"</d:getetag>
              <d:getlastmodified>Wed, 18 Jun 2026 10:00:00 GMT</d:getlastmodified>
              <d:resourcetype/>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/YuMark/%E4%B8%AD%E6%96%87.md</d:href>
            <d:propstat><d:prop>
              <d:getetag>W/"weak-1"</d:getetag>
              <d:resourcetype/>
            </d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/YuMark/sub/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `parses files and directories`() {
        val entries = WebDavXml.parseMultistatus(multistatus)
        assertThat(entries).hasSize(4)
        val files = entries.filter { !it.isDirectory && it.name.endsWith(".md") }
        assertThat(files.map { it.name }).containsExactly("Note.md", "中文.md")
    }

    @Test
    fun `normalizes etag and parses last-modified`() {
        val note = WebDavXml.parseMultistatus(multistatus).first { it.name == "Note.md" }
        assertThat(note.etag).isEqualTo("abc123")           // 去掉包裹引号
        assertThat(note.lastModifiedMs).isNotNull()
        assertThat(note.isDirectory).isFalse()
    }

    @Test
    fun `decodes percent-encoded names and strips weak etag prefix`() {
        val cn = WebDavXml.parseMultistatus(multistatus).first { it.name == "中文.md" }
        assertThat(cn.etag).isEqualTo("weak-1")             // 去掉 W/ 与引号
        assertThat(cn.lastModifiedMs).isNull()              // 缺 getlastmodified
    }

    @Test
    fun `directory entries are flagged`() {
        val dirs = WebDavXml.parseMultistatus(multistatus).filter { it.isDirectory }
        assertThat(dirs.map { it.name }).containsExactly("YuMark", "sub")
    }

    @Test
    fun `is namespace-prefix agnostic`() {
        // 用大写前缀 D: 同样应解析成功（命名空间感知，不依赖前缀字面）
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/YuMark/A.md</D:href>
                <D:propstat><D:prop><D:getetag>"e1"</D:getetag><D:resourcetype/></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = WebDavXml.parseMultistatus(xml)
        assertThat(entries).hasSize(1)
        assertThat(entries[0].name).isEqualTo("A.md")
        assertThat(entries[0].etag).isEqualTo("e1")
    }
}
