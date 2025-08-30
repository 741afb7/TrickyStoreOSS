/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS

import io.github.beakthoven.TrickyStoreOSS.logging.Logger
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

object CertificateHack {    
    private val certificateFactory: CertificateFactory by lazy {
        try {
            CertificateFactory.getInstance("X.509")
        } catch (t: Throwable) {
            Logger.e("Failed to initialize certificate factory", t)
            throw RuntimeException("Cannot initialize certificate factory", t)
        }
    }
    
    data class KeyIdentifier(
        val alias: String,
        val uid: Int
    )

    val leafAlgorithms = ConcurrentHashMap<KeyIdentifier, String>()
    
    fun clearLeafAlgorithms() {
        leafAlgorithms.clear()
    }
    
    fun hackCertificateChain(certificateChain: Array<Certificate>?): Array<Certificate> {
        if (certificateChain == null) {
            throw UnsupportedOperationException("Certificate chain is null!")
        }
        
        return try {
            val leaf = certificateFactory.generateCertificate(
                ByteArrayInputStream(certificateChain[0].encoded)
            ) as X509Certificate
            
            val extensionBytes = leaf.getExtensionValue(ATTESTATION_OID.id)
                ?: return certificateChain // No attestation extension, return original

            val leafHolder = X509CertificateHolder(leaf.encoded)
            val extension = leafHolder.getExtension(ATTESTATION_OID)
            val sequence = ASN1Sequence.getInstance(extension.extnValue.octets)
            val encodables = sequence.toArray()
            val teeEnforced = encodables[7] as ASN1Sequence
            
            val vector = ASN1EncodableVector()
            var rootOfTrust: ASN1Encodable? = null
            
            teeEnforced.forEach { element ->
                val taggedObject = element as ASN1TaggedObject
                if (taggedObject.tagNo == 704) {
                    rootOfTrust = taggedObject.baseObject.toASN1Primitive()
                } else {
                    vector.add(taggedObject)
                }
            }
            
            val keybox = KeyBoxUtils.keyboxes[leaf.publicKey.algorithm]
                ?: throw UnsupportedOperationException("Unsupported algorithm: ${leaf.publicKey.algorithm}")
            
            val certificates = LinkedList(keybox.certificates)
            val builder = X509v3CertificateBuilder(
                X509CertificateHolder(certificates[0].encoded).subject,
                leafHolder.serialNumber,
                leafHolder.notBefore,
                leafHolder.notAfter,
                leafHolder.subject,
                leafHolder.subjectPublicKeyInfo
            )
            
            val signer = JcaContentSignerBuilder(leaf.sigAlgName).build(keybox.keyPair.private)
            
            val hackedExtension = hackAttestExtension(rootOfTrust, vector, encodables)
            builder.addExtension(hackedExtension)
            
            leafHolder.extensions.extensionOIDs.forEach { oid ->
                if (oid.id != ATTESTATION_OID.id) {
                    builder.addExtension(leafHolder.getExtension(oid))
                }
            }
            
            certificates.addFirst(JcaX509CertificateConverter().getCertificate(builder.build(signer)))
            certificates.toTypedArray()
        } catch (t: Throwable) {
            Logger.e("Failed to hack certificate chain", t)
            certificateChain
        }
    }
    
    fun hackCACertificateChain(caList: ByteArray?, alias: String, uid: Int): ByteArray {
        if (caList == null) {
            throw UnsupportedOperationException("CA list is null!")
        }
        
        return try {
            val key = KeyIdentifier(alias, uid)
            val algorithm = leafAlgorithms.remove(key)
                ?: throw UnsupportedOperationException("No algorithm found for key $key")
            
            val keybox = KeyBoxUtils.keyboxes[algorithm]
                ?: throw UnsupportedOperationException("Unsupported algorithm: $algorithm")
            
            CertificateUtils.run { keybox.certificates.toByteArray() } ?: caList
        } catch (t: Throwable) {
            Logger.e("Failed to hack CA certificate chain", t)
            caList
        }
    }
    
    fun hackUserCertificate(certificate: ByteArray?, alias: String, uid: Int): ByteArray {
        if (certificate == null) {
            throw UnsupportedOperationException("Leaf certificate is null!")
        }
        
        return try {
            val leaf = certificateFactory.generateCertificate(
                ByteArrayInputStream(certificate)
            ) as X509Certificate
            
            val extensionBytes = leaf.getExtensionValue(ATTESTATION_OID.id)
                ?: return certificate // No attestation extension, return original
            
            val keyIdentifier = KeyIdentifier(alias, uid)
            leafAlgorithms[keyIdentifier] = leaf.publicKey.algorithm
            
            val leafHolder = X509CertificateHolder(leaf.encoded)
            val extension = leafHolder.getExtension(ATTESTATION_OID)
            val sequence = ASN1Sequence.getInstance(extension.extnValue.octets)
            val encodables = sequence.toArray()
            val teeEnforced = encodables[7] as ASN1Sequence
            
            val vector = ASN1EncodableVector()
            var rootOfTrust: ASN1Encodable? = null
            
            teeEnforced.forEach { element ->
                val taggedObject = element as ASN1TaggedObject
                if (taggedObject.tagNo == 704) {
                    rootOfTrust = taggedObject.baseObject.toASN1Primitive()
                } else {
                    vector.add(taggedObject)
                }
            }
            
            val keybox = KeyBoxUtils.keyboxes[leaf.publicKey.algorithm]
                ?: throw UnsupportedOperationException("Unsupported algorithm: ${leaf.publicKey.algorithm}")
            
            val builder = X509v3CertificateBuilder(
                X509CertificateHolder(keybox.certificates[0].encoded).subject,
                leafHolder.serialNumber,
                leafHolder.notBefore,
                leafHolder.notAfter,
                leafHolder.subject,
                leafHolder.subjectPublicKeyInfo
            )
            
            val signer = JcaContentSignerBuilder(leaf.sigAlgName).build(keybox.keyPair.private)
            
            val hackedExtension = hackAttestExtension(rootOfTrust, vector, encodables)
            builder.addExtension(hackedExtension)
            
            leafHolder.extensions.extensionOIDs.forEach { oid ->
                if (oid.id != ATTESTATION_OID.id) {
                    builder.addExtension(leafHolder.getExtension(oid))
                }
            }

            JcaX509CertificateConverter().getCertificate(builder.build(signer)).encoded
        } catch (t: Throwable) {
            Logger.e("Failed to hack user certificate", t)
            certificate
        }
    }
    
    private fun hackAttestExtension(
        originalRootOfTrust: ASN1Encodable?,
        vector: ASN1EncodableVector,
        originalEncodables: Array<ASN1Encodable>
    ): Extension {
        val verifiedBootKey = AndroidUtils.bootKey
        var verifiedBootHash: ByteArray? = null
        
        try {
            if (originalRootOfTrust is ASN1Sequence) {
                verifiedBootHash = CertificateUtils.getByteArrayFromAsn1(originalRootOfTrust.getObjectAt(3))
            }
        } catch (t: Throwable) {
            Logger.e("Failed to get verified boot hash from original, using generated", t)
        }
        
        if (verifiedBootHash == null) {
            verifiedBootHash = AndroidUtils.getBootHashFromProp()
        }
        
        val rootOfTrustElements = arrayOf(
            DEROctetString(verifiedBootKey),
            ASN1Boolean.TRUE,
            ASN1Enumerated(0),
            DEROctetString(verifiedBootHash)
        )
        val hackedRootOfTrust = DERSequence(rootOfTrustElements)
        
        vector.add(DERTaggedObject(true, 718, ASN1Integer(AndroidUtils.vendorPatchLevelLong.toLong())))
        vector.add(DERTaggedObject(true, 719, ASN1Integer(AndroidUtils.bootPatchLevelLong.toLong())))
        vector.add(DERTaggedObject(true, 706, ASN1Integer(AndroidUtils.patchLevel.toLong())))
        vector.add(DERTaggedObject(true, 705, ASN1Integer(AndroidUtils.osVersion.toLong())))
        vector.add(DERTaggedObject(704, hackedRootOfTrust))
        
        val hackEnforced = DERSequence(vector)
        originalEncodables[7] = hackEnforced
        val hackedSequence = DERSequence(originalEncodables)
        val hackedSequenceOctets = DEROctetString(hackedSequence)
        
        return Extension(ATTESTATION_OID, false, hackedSequenceOctets)
    }

}