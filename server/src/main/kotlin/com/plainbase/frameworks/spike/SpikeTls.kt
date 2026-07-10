package com.plainbase.frameworks.spike

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Spike check #1's TLS material: a self-signed loopback certificate generated at RUN time inside
 * the (possibly native) binary - fresh RSA keypair and SecureRandom serial per run, so build-time
 * -frozen entropy or a missing in-image crypto provider fails loudly. The cert is built from
 * Bouncy Castle's ASN.1 primitives (bcprov is already the argon2 dependency; the higher-level
 * cert builders live in bcpkix, which is deliberately NOT taken) and signed with JDK
 * `SHA256withRSA`.
 *
 * Trust is PINNED: [clientTrust] is a real `TrustManagerFactory` path-validation trust manager
 * over exactly this one certificate - never a trust-all stub - so the round-trip proves the CIO
 * client's certificate validation actually runs in the native image.
 */
internal object SpikeTls {

    class Loopback(val serverContext: SSLContext, val clientTrust: X509TrustManager)

    fun selfSignedLoopback(): Loopback {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = selfSign(keyPair)

        val password = CharArray(0)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("spike", keyPair.private, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
            .keyManagers
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }

        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("spike", certificate)
        }
        val clientTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()

        return Loopback(serverContext, clientTrust)
    }

    private fun selfSign(keyPair: KeyPair): X509Certificate {
        val name = X500Name("CN=127.0.0.1")
        val now = Instant.now()
        val signatureAlgorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val subjectAltNames = GeneralNames(
            arrayOf(GeneralName(GeneralName.iPAddress, "127.0.0.1"), GeneralName(GeneralName.dNSName, "localhost")),
        )
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(64, SecureRandom()).add(BigInteger.ONE)))
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date.from(now.minus(1, ChronoUnit.HOURS))))
            setEndDate(Time(Date.from(now.plus(1, ChronoUnit.DAYS))))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keyPair.public.encoded))
            setSignature(signatureAlgorithm)
            setExtensions(
                Extensions(Extension(Extension.subjectAlternativeName, false, subjectAltNames.getEncoded(ASN1Encoding.DER))),
            )
        }.generateTBSCertificate()

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs.getEncoded(ASN1Encoding.DER))
            sign()
        }
        val certificate = org.bouncycastle.asn1.x509.Certificate.getInstance(
            DERSequence(arrayOf<ASN1Encodable>(tbs, signatureAlgorithm, DERBitString(signature))),
        )
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.getEncoded(ASN1Encoding.DER).inputStream()) as X509Certificate
    }
}
