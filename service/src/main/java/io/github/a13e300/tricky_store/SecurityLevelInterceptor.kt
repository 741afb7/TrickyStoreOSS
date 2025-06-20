package io.github.a13e300.tricky_store

import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.SecurityLevel
import android.hardware.security.keymint.Tag
import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.Authorization
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import android.system.keystore2.KeyMetadata
import io.github.a13e300.tricky_store.binder.BinderInterceptor
import io.github.a13e300.tricky_store.keystore.CertHack
import io.github.a13e300.tricky_store.keystore.CertHack.KeyGenParameters
import io.github.a13e300.tricky_store.keystore.Utils
import java.security.KeyPair
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap

class SecurityLevelInterceptor(
    private val original: IKeystoreSecurityLevel,
    private val level: Int
) : BinderInterceptor() {

    init {
        strongBox = level == SecurityLevel.STRONGBOX
    }
    companion object {
        private val generateKeyTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "generateKey")
        private val deleteKeyTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "deleteKey")
        private val createOperationTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "createOperation")

        val keys = ConcurrentHashMap<Key, Info>()
        val keyPairs = ConcurrentHashMap<Key, Pair<KeyPair, List<Certificate>>>()

        fun getKeyResponse(uid: Int, alias: String): KeyEntryResponse? =
            keys[Key(uid, alias)]?.response
        fun getKeyPairs(uid: Int, alias: String): Pair<KeyPair, List<Certificate>>? =
            keyPairs[Key(uid, alias)]
    }

    data class Key(val uid: Int, val alias: String)
    data class Info(val keyPair: KeyPair, val response: KeyEntryResponse)

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel
    ): Result {
        if (code == generateKeyTransaction && Config.needGenerate(callingUid)) {
            Logger.i("intercept key gen uid=$callingUid pid=$callingPid")
            kotlin.runCatching {
                data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
                val keyDescriptor =
                    data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching
                val attestationKeyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR)
                val params = data.createTypedArray(KeyParameter.CREATOR)!!
                val aFlags = data.readInt()
                val entropy = data.createByteArray()
                val kgp = KeyGenParameters(params)
                // Logger.e("warn: attestation key not supported now")
                val pair = CertHack.generateKeyPair(callingUid, keyDescriptor, attestationKeyDescriptor, kgp)
                    ?: return@runCatching
                keyPairs[Key(callingUid, keyDescriptor.alias)] = Pair(pair.first!!, pair.second!!)
                val response = buildResponse(pair.second, kgp, attestationKeyDescriptor ?: keyDescriptor)
                keys[Key(callingUid, keyDescriptor.alias)] = Info(pair.first, response)
                val p = Parcel.obtain()
                p.writeNoException()
                p.writeTypedObject(response.metadata, 0)
                return OverrideReply(0, p)
            }.onFailure {
                Logger.e("parse key gen request", it)
            }
        }
        return Skip
    }

    private fun buildResponse(
        chain: List<Certificate>,
        params: KeyGenParameters,
        descriptor: KeyDescriptor
    ): KeyEntryResponse {
        val response = KeyEntryResponse()
        val metadata = KeyMetadata()
        metadata.keySecurityLevel = level
        Utils.putCertificateChain(metadata, chain.toTypedArray<Certificate>())
        val d = KeyDescriptor()
        d.domain = descriptor.domain
        d.nspace = descriptor.nspace
        metadata.key = d
        val authorizations = ArrayList<Authorization>()
        var a: Authorization
        for (i in params.purpose) {
            a = Authorization()
            a.keyParameter = KeyParameter()
            a.keyParameter.tag = Tag.PURPOSE
            a.keyParameter.value = KeyParameterValue.keyPurpose(i)
            a.securityLevel = level
            authorizations.add(a)
        }
        for (i in params.digest) {
            a = Authorization()
            a.keyParameter = KeyParameter()
            a.keyParameter.tag = Tag.DIGEST
            a.keyParameter.value = KeyParameterValue.digest(i)
            a.securityLevel = level
            authorizations.add(a)
        }
        a = Authorization()
        a.keyParameter = KeyParameter()
        a.keyParameter.tag = Tag.ALGORITHM
        a.keyParameter.value = KeyParameterValue.algorithm(params.algorithm)
        a.securityLevel = level
        authorizations.add(a)
        a = Authorization()
        a.keyParameter = KeyParameter()
        a.keyParameter.tag = Tag.KEY_SIZE
        a.keyParameter.value = KeyParameterValue.integer(params.keySize)
        a.securityLevel = level
        authorizations.add(a)
        a = Authorization()
        a.keyParameter = KeyParameter()
        a.keyParameter.tag = Tag.EC_CURVE
        a.keyParameter.value = KeyParameterValue.ecCurve(params.ecCurve)
        a.securityLevel = level
        authorizations.add(a)
        a = Authorization()
        a.keyParameter = KeyParameter()
        a.keyParameter.tag = Tag.NO_AUTH_REQUIRED
        a.keyParameter.value = KeyParameterValue.boolValue(true) // TODO: copy
        a.securityLevel = level
        authorizations.add(a)
        // TODO: ORIGIN
        //OS_VERSION
        //OS_PATCHLEVEL
        //VENDOR_PATCHLEVEL
        //BOOT_PATCHLEVEL
        //CREATION_DATETIME
        //USER_ID
        metadata.authorizations = authorizations.toTypedArray<Authorization>()
        response.metadata = metadata
        response.iSecurityLevel = original
        return response
    }
}
