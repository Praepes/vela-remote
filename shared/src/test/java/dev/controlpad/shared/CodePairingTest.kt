package dev.controlpad.shared

import org.junit.Assert.*
import org.junit.Test

class CodePairingTest {
    private val certificate="a".repeat(64)
    private fun exchange(a:CodePairing,b:CodePairing) {
        val oneA=a.roundOne(); val oneB=b.roundOne(); a.acceptOne(oneB); b.acceptOne(oneA)
        val twoA=a.roundTwo(); val twoB=b.roundTwo(); a.acceptTwo(twoB); b.acceptTwo(twoA)
    }
    @Test fun sixDigitsAuthenticateBothRolesAndCertificate() {
        val a=CodePairing("controller","012345"); val b=CodePairing("receiver","012345"); exchange(a,b)
        val ac=a.confirm(certificate,"controller"); val bc=b.confirm(certificate,"receiver")
        b.verify(ac,certificate,"controller"); a.verify(bc,certificate,"receiver")
    }
    @Test fun wrongCodeCannotConfirm() {
        val a=CodePairing("controller","012345"); val b=CodePairing("receiver","999999"); exchange(a,b)
        assertThrows(Exception::class.java) { b.verify(a.confirm(certificate,"controller"),certificate,"controller") }
    }
    @Test fun relayingAcrossDifferentTlsCertificatesFails() {
        val a=CodePairing("controller","012345"); val b=CodePairing("receiver","012345"); exchange(a,b)
        assertThrows(Exception::class.java) { b.verify(a.confirm(certificate,"controller"),"b".repeat(64),"controller") }
    }
    @Test fun roleReflectionFails() {
        val a=CodePairing("controller","012345"); val b=CodePairing("receiver","012345"); exchange(a,b)
        assertThrows(Exception::class.java) { b.verify(a.confirm(certificate,"controller"),certificate,"receiver") }
    }
    @Test fun malformedOrWrongIdentityIsRejected() {
        val a=CodePairing("controller","012345"); val b=CodePairing("receiver","012345"); a.roundOne(); val p=b.roundOne()
        assertThrows(Exception::class.java) { a.acceptOne(p,"another-device") }
        assertThrows(Exception::class.java) { CodePairing("controller","012345").acceptOne(byteArrayOf(0)) }
    }
    @Test fun onlySixAsciiDigitsAreAccepted() { listOf("12345","1234567","abcdef","１２３４５６").forEach { code->assertThrows(IllegalArgumentException::class.java) { CodePairing("controller",code) } } }
}
