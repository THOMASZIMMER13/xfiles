package io.github.thomaszimmer13.xfiles.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Audit N-02 et N-06 : réponses WebDAV aux href absolus, HTTP limité au réseau local. */
class WebDavRemoteTest {

    private fun multistatus(vararg hrefs: Pair<String, Boolean>) = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
        ${hrefs.joinToString("") { (href, dir) -> """
          <d:response><d:href>$href</d:href><d:propstat><d:prop>
            <d:resourcetype>${if (dir) "<d:collection/>" else ""}</d:resourcetype>
            ${if (dir) "" else "<d:getcontentlength>1234</d:getcontentlength>"}
            <d:getlastmodified>Tue, 03 Mar 2026 10:00:00 GMT</d:getlastmodified>
          </d:prop></d:propstat></d:response>""" }}
        </d:multistatus>""".byteInputStream()

    @Test fun absoluteHrefs() {
        val xml = multistatus(
            "https://nas.local/dav/Documents/" to true,
            "https://nas.local/dav/Documents/fichier.txt" to false,
            "https://nas.local/dav/Documents/Photos%20d%27%C3%A9t%C3%A9/" to true,
            "https://nas.local/dav/Documents/a+b.txt" to false,
        )
        val entries = WebDavRemote.parseMultistatus(xml, "/Documents", "/dav/Documents/")
        assertEquals(listOf("fichier.txt", "Photos d'été", "a+b.txt"), entries.map { it.name })
        val file = entries.first()
        assertEquals("/Documents/fichier.txt", file.path)
        assertEquals(1234, file.size)
        assertFalse(file.isDirectory)
        assertTrue(file.lastModified > 0)
        assertTrue(entries[1].isDirectory)
    }

    @Test fun relativeHrefsAndTraps() {
        val xml = multistatus(
            "/dav/" to true,
            "/dav/note.md" to false,
            "/dav/%2e%2e%2fsecret" to false, // décodé : « ../secret », refusé
            "/dav/%252e%252e%252f" to false, // double encodage : nom littéral « %2e%2e%2f », sans danger
            "http://autre/dav/x%00y" to false,
        )
        val names = WebDavRemote.parseMultistatus(xml, "/", "/dav/").map { it.name }
        assertEquals(listOf("note.md", "%2e%2e%2f"), names)
    }

    @Test fun externalEntitiesAreRefused() {
        val xml = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/&x;</d:href></d:response></d:multistatus>""".byteInputStream()
        try {
            WebDavRemote.parseMultistatus(xml, "/", "/dav/")
            fail("DTD acceptée")
        } catch (_: Exception) {
        }
    }

    @Test fun cleartextOnlyOnTheLocalNetwork() {
        for (host in listOf("192.168.1.10", "10.0.0.2", "172.16.5.1", "172.31.255.255", "127.0.0.1", "169.254.1.1", "100.100.1.1", "nas", "nas.local", "box.lan", "maison.home.arpa", "localhost", "[fe80::1]", "fd12::3", "::1", "NAS.LOCAL.")) {
            assertTrue(host, LocalNetwork.isLocalHost(host))
        }
        for (host in listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1", "cloud.exemple.fr", "nas.local.exemple.com", "2001:db8::1", "", "1.2.3", "256.1.1.1")) {
            assertFalse(host, LocalNetwork.isLocalHost(host))
        }
        try {
            WebDavRemote(RemoteServer(id = "t", protocol = Protocol.WEBDAV, name = "t", host = "dav.exemple.com", port = 80, secure = false))
            fail("HTTP vers Internet accepté")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.CLEARTEXT, e.reason)
        }
    }
}
