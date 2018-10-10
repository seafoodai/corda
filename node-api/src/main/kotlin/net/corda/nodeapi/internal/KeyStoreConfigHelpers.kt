package net.corda.nodeapi.internal

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.Crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.toX500Name
import net.corda.nodeapi.internal.config.CertificateStore
import net.corda.nodeapi.internal.crypto.*
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.NameConstraints
import java.security.KeyPair
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

// TODO Merge this file and DevIdentityGenerator

/**
 * Create the node and SSL key stores needed by a node. The node key store will be populated with a node CA cert (using
 * the given legal name), and the SSL key store will store the TLS cert which is a sub-cert of the node CA.
 */

fun CertificateStore.registerDevSigningCertificates(legalName: CordaX500Name,
                                                    rootCert: X509Certificate = DEV_ROOT_CA.certificate,
                                                    intermediateCa: CertificateAndKeyPair = DEV_INTERMEDIATE_CA,
                                                    devNodeCa: CertificateAndKeyPair = createDevNodeCa(intermediateCa, legalName)) {

    update {
        setPrivateKey(X509Utilities.CORDA_CLIENT_CA, devNodeCa.keyPair.private, listOf(devNodeCa.certificate, intermediateCa.certificate, rootCert))
    }
}

fun CertificateStore.registerDevP2pCertificates(legalName: CordaX500Name,
                                                rootCert: X509Certificate = DEV_ROOT_CA.certificate,
                                                intermediateCa: CertificateAndKeyPair = DEV_INTERMEDIATE_CA,
                                                devNodeCa: CertificateAndKeyPair = createDevNodeCa(intermediateCa, legalName)) {

    update {
        val tlsKeyPair = generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val tlsCert = X509Utilities.createCertificate(CertificateType.TLS, devNodeCa.certificate, devNodeCa.keyPair, legalName.x500Principal, tlsKeyPair.public)
        setPrivateKey(X509Utilities.CORDA_CLIENT_TLS, tlsKeyPair.private, listOf(tlsCert, devNodeCa.certificate, intermediateCa.certificate, rootCert))
    }
}

fun CertificateStore.storeLegalIdentity(alias: String, keyPair: KeyPair = Crypto.generateKeyPair()): PartyAndCertificate {
    val identityCertPath = query {
        val nodeCaCertPath = getCertificateChain(X509Utilities.CORDA_CLIENT_CA)
        // Assume key password = store password.
        val nodeCaCertAndKeyPair = getCertificateAndKeyPair(X509Utilities.CORDA_CLIENT_CA)
        // Create new keys and store in keystore.
        val identityCert = X509Utilities.createCertificate(CertificateType.LEGAL_IDENTITY, nodeCaCertAndKeyPair.certificate, nodeCaCertAndKeyPair.keyPair, nodeCaCertAndKeyPair.certificate.subjectX500Principal, keyPair.public)
        // TODO: X509Utilities.validateCertificateChain()
        // Assume key password = store password.
        listOf(identityCert) + nodeCaCertPath
    }
    update {
        setPrivateKey(alias, keyPair.private, identityCertPath)
    }
    return PartyAndCertificate(X509Utilities.buildCertPath(identityCertPath))
}

fun createDevNetworkMapCa(rootCa: CertificateAndKeyPair = DEV_ROOT_CA): CertificateAndKeyPair {
    val keyPair = generateKeyPair()
    val cert = X509Utilities.createCertificate(
            CertificateType.NETWORK_MAP,
            rootCa.certificate,
            rootCa.keyPair,
            X500Principal("CN=Network Map,O=R3 Ltd,L=London,C=GB"),
            keyPair.public)
    return CertificateAndKeyPair(cert, keyPair)
}

/**
 * Create a dev node CA cert, as a sub-cert of the given [intermediateCa], and matching key pair using the given
 * [CordaX500Name] as the cert subject.
 */
fun createDevNodeCa(intermediateCa: CertificateAndKeyPair,
                    legalName: CordaX500Name,
                    nodeKeyPair: KeyPair = generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)): CertificateAndKeyPair {
    val nameConstraints = NameConstraints(arrayOf(GeneralSubtree(GeneralName(GeneralName.directoryName, legalName.toX500Name()))), arrayOf())
    val cert = X509Utilities.createCertificate(
            CertificateType.NODE_CA,
            intermediateCa.certificate,
            intermediateCa.keyPair,
            legalName.x500Principal,
            nodeKeyPair.public,
            nameConstraints = nameConstraints)
    return CertificateAndKeyPair(cert, nodeKeyPair)
}

val DEV_INTERMEDIATE_CA: CertificateAndKeyPair get() = DevCaHelper.loadDevCa(X509Utilities.CORDA_INTERMEDIATE_CA)
val DEV_ROOT_CA: CertificateAndKeyPair get() = DevCaHelper.loadDevCa(X509Utilities.CORDA_ROOT_CA)
const val DEV_CA_PRIVATE_KEY_PASS: String = "cordacadevkeypass"
const val DEV_CA_KEY_STORE_FILE: String = "cordadevcakeys.jks"
const val DEV_CA_KEY_STORE_PASS: String = "cordacadevpass"
const val DEV_CA_TRUST_STORE_FILE: String = "cordatruststore.jks"
const val DEV_CA_TRUST_STORE_PASS: String = "trustpass"

val DEV_CERTIFICATES: List<X509Certificate> get() = listOf(DEV_INTERMEDIATE_CA.certificate, DEV_ROOT_CA.certificate)

// We need a class so that we can get hold of the class loader
internal object DevCaHelper {
    fun loadDevCa(alias: String): CertificateAndKeyPair {
        return loadDevCaKeyStore().query { getCertificateAndKeyPair(alias, DEV_CA_PRIVATE_KEY_PASS) }
    }
}

fun loadDevCaKeyStore(classLoader: ClassLoader = DevCaHelper::class.java.classLoader): CertificateStore = CertificateStore.fromResource("certificates/$DEV_CA_KEY_STORE_FILE", DEV_CA_KEY_STORE_PASS, classLoader)

fun loadDevCaTrustStore(classLoader: ClassLoader = DevCaHelper::class.java.classLoader): CertificateStore = CertificateStore.fromResource("certificates/$DEV_CA_TRUST_STORE_FILE", DEV_CA_TRUST_STORE_PASS, classLoader)
