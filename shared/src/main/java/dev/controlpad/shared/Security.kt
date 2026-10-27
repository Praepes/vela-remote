package dev.controlpad.shared

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.net.Socket
import java.util.Date
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

object Security {
    fun randomToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    fun equalsSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
    fun identity(): Pair<SSLContext,String> {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val alias="controlpad_tls_v2"
        if(!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=ControlPad"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(0)).setCertificateNotAfter(Date(4102444800000L)).build())
            }.generateKeyPair()
        }
        val cert=store.getCertificate(alias) as X509Certificate
        val manager=object:X509KeyManager {
            override fun chooseClientAlias(a:Array<out String>?,i:Array<out java.security.Principal>?,s:Socket?)=alias
            override fun chooseServerAlias(a:String?,i:Array<out java.security.Principal>?,s:Socket?)=if(a?.startsWith("EC")==true) alias else null
            override fun getCertificateChain(a:String?)=arrayOf(cert)
            override fun getPrivateKey(a:String?)=store.getKey(alias,null) as PrivateKey
            override fun getClientAliases(a:String?,i:Array<out java.security.Principal>?)=arrayOf(alias)
            override fun getServerAliases(a:String?,i:Array<out java.security.Principal>?)=if(a?.startsWith("EC")==true) arrayOf(alias) else null
        }
        val ssl=SSLContext.getInstance("TLS").apply { init(arrayOf(manager),null,SecureRandom()) }
        return ssl to fingerprint(cert)
    }
    fun pinnedContext(pin: String, found: (String)->Unit = {}): SSLContext {
        require(pin.length>=12 && pin.length<=64 && pin.all { it in "0123456789abcdef" }) { "需填写设备指纹（至少 12 位）" }
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?) { throw java.security.cert.CertificateException("不接受客户端证书") }
            override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?) {
                val cert=c?.firstOrNull() ?: throw java.security.cert.CertificateException("缺少设备证书")
                val fp=fingerprint(cert)
                if(!fp.startsWith(pin)) throw java.security.cert.CertificateException("设备指纹不一致，请在受控端核对")
                cert.checkValidity(); found(fp)
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),SecureRandom()) }
    }
    /** 仅供 J-PAKE 建链使用：此阶段绝不发送配对码或长期凭据。 */
    fun codePairingContext(found:(String)->Unit):SSLContext {
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?) {
                val cert=c?.firstOrNull() ?: throw java.security.cert.CertificateException("缺少设备证书")
                cert.checkValidity(); found(fingerprint(cert))
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),SecureRandom()) }
    }
    fun harden(socket: SSLSocket) { socket.enabledProtocols=socket.supportedProtocols.filter { it=="TLSv1.2" || it=="TLSv1.3" }.toTypedArray(); socket.tcpNoDelay=true }
}
