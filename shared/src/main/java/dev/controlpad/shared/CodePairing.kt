package dev.controlpad.shared

import org.bouncycastle.crypto.agreement.jpake.*
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.*
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 六位短码的 J-PAKE 交换；双方确认密钥并绑定实际 TLS 证书后才能保存配对。 */
class CodePairing(private val identity:String,code:String) {
    private val participant:JPAKEParticipant
    private lateinit var key:BigInteger
    private lateinit var confirmation:JPAKERound3Payload
    init {
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位数字配对码" }
        val password=code.toCharArray()
        participant=JPAKEParticipant(identity,password)
        password.fill('\u0000')
    }
    private fun pack(block:DataOutputStream.()->Unit):ByteArray=ByteArrayOutputStream().also { out->DataOutputStream(out).use { it.block() } }.toByteArray()
    private fun <T> unpack(bytes:ByteArray,block:DataInputStream.()->T):T {
        require(bytes.size in 1..8192) { "配对消息长度无效" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input->val result=input.block(); require(input.available()==0); result }
    }
    private fun DataOutputStream.number(value:BigInteger) { val bytes=value.toByteArray(); writeShort(bytes.size); write(bytes) }
    private fun DataInputStream.number():BigInteger { val size=readUnsignedShort(); require(size in 1..385); return BigInteger(ByteArray(size).also { readFully(it) }) }
    private fun DataInputStream.identifier():String=readUTF().also { require(it.length in 1..128) }
    fun roundOne():ByteArray {
        val p=participant.createRound1PayloadToSend()
        return pack { writeUTF(p.participantId); number(p.gx1); number(p.gx2); p.knowledgeProofForX1.forEach { number(it) }; p.knowledgeProofForX2.forEach { number(it) } }
    }
    fun acceptOne(bytes:ByteArray,expectedIdentity:String?=null) {
        val p=unpack(bytes) { JPAKERound1Payload(identifier(),number(),number(),arrayOf(number(),number()),arrayOf(number(),number())) }
        require(expectedIdentity==null || p.participantId==expectedIdentity) { "配对设备身份不符" }
        participant.validateRound1PayloadReceived(p)
    }
    fun roundTwo():ByteArray {
        val p=participant.createRound2PayloadToSend()
        return pack { writeUTF(p.participantId); number(p.a); p.knowledgeProofForX2s.forEach { number(it) } }
    }
    fun acceptTwo(bytes:ByteArray) {
        participant.validateRound2PayloadReceived(unpack(bytes) { JPAKERound2Payload(identifier(),number(),arrayOf(number(),number())) })
        key=participant.calculateKeyingMaterial()
        confirmation=participant.createRound3PayloadToSend(key)
    }
    private fun binding(certificate:String,role:String):ByteArray {
        require(certificate.matches(Regex("[a-f0-9]{64}")))
        val derived=ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(key.toByteArray(),null,"controlpad-code-pairing-v1".toByteArray())); generateBytes(derived,0,derived.size) }
        return try { Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(derived,"HmacSHA256")) }.doFinal("$role:$certificate".toByteArray()) } finally { derived.fill(0) }
    }
    fun confirm(certificate:String,role:String):ByteArray=pack {
        writeUTF(identity); number(confirmation.macTag); write(binding(certificate,role))
    }
    fun verify(bytes:ByteArray,certificate:String,peerRole:String) {
        val p=unpack(bytes) { val payload=JPAKERound3Payload(identifier(),number()); val tag=ByteArray(32).also { readFully(it) }; payload to tag }
        participant.validateRound3PayloadReceived(p.first,key)
        require(MessageDigest.isEqual(p.second,binding(certificate,peerRole))) { "配对通道校验失败" }
    }
}
