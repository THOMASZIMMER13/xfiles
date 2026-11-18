package io.github.thomaszimmer13.xfiles.data.remote

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date

/** H-01 : FTPS ne doit plus accepter n'importe quel certificat en cours de validité. */
class FtpsTrustTest {
    private fun selfSigned(cn: String): X509Certificate {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            X500Name("CN=$cn"), BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), X500Name("CN=$cn"), keys.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun refused(trust: FtpsTrust, cert: X509Certificate) {
        try {
            trust.checkServerTrusted(arrayOf(cert), "RSA")
            fail("certificat accepté")
        } catch (_: CertificateException) {
        }
    }

    @Test fun selfSignedIsRefusedThenOfferedForTrust() {
        val cert = selfSigned("nas.local")
        val trust = FtpsTrust(pinned = "")
        refused(trust, cert) // magasin d'autorités : refus (avant : accepté, dates seulement)
        val offer = trust.hostKeyFailure()!!
        assertFalse(offer.changed)
        assertEquals(FtpsTrust.fingerprint(cert), offer.fingerprint)
    }

    @Test fun pinnedCertificateIsAcceptedAndOtherIsRefusedAsChanged() {
        val cert = selfSigned("nas.local")
        val pinned = FtpsTrust.fingerprint(cert)
        val ok = FtpsTrust(pinned)
        ok.checkServerTrusted(arrayOf(cert), "RSA")
        assertNull(ok.hostKeyFailure())

        val attacker = selfSigned("nas.local")
        val trust = FtpsTrust(pinned)
        refused(trust, attacker)
        assertTrue(trust.hostKeyFailure()!!.changed)
    }

    @Test fun unrelatedErrorIsNotTurnedIntoTrustQuestion() {
        val cert = selfSigned("nas.local")
        val trust = FtpsTrust(FtpsTrust.fingerprint(cert))
        trust.checkServerTrusted(arrayOf(cert), "RSA")
        assertNull(trust.hostKeyFailure()) // panne réseau après une négociation réussie
        assertTrue(trust.hostKeyFailure(hostnameMismatch = false) == null)
    }

    @Test fun hostnameMismatchOnValidChainOffersTrust() {
        val cert = selfSigned("autre.exemple")
        val trust = FtpsTrust(pinned = "", system = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit // chaîne valide
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        trust.checkServerTrusted(arrayOf(cert), "RSA")
        assertNull(trust.hostKeyFailure())
        assertFalse(trust.hostKeyFailure(hostnameMismatch = true)!!.changed)
    }
}
