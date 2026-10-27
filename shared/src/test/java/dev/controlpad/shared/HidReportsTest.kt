package dev.controlpad.shared

import org.junit.Assert.*
import org.junit.Test

class HidReportsTest {
    @Test fun slowMotionAccumulatesInsteadOfDisappearing() {
        val reports=mutableListOf<ByteArray>(); val mouse=HidMouse(reports::add)
        repeat(4) { mouse.move(0.25f,-0.25f) }
        assertEquals(1,reports.size); assertArrayEquals(byteArrayOf(0,1,-1,0),reports.single())
    }
    @Test fun largeMovementIsSplitWithoutLosingDistance() {
        val reports=mutableListOf<ByteArray>(); val mouse=HidMouse(reports::add)
        mouse.move(400f,-300f)
        assertEquals(400,reports.sumOf { it[1].toInt() }); assertEquals(-300,reports.sumOf { it[2].toInt() })
        assertTrue(reports.all { it.size==4 && it[1].toInt() in -127..127 && it[2].toInt() in -127..127 })
    }
    @Test fun dragKeepsButtonDownAcrossEveryMovementReport() {
        val reports=mutableListOf<ByteArray>(); val mouse=HidMouse(reports::add)
        mouse.down(); mouse.move(300f,0f); mouse.up()
        assertTrue(reports.dropLast(1).all { it[0].toInt()==1 }); assertEquals(0,reports.last()[0].toInt())
    }
    @Test fun cancellingReleasesButtonAndDropsFractionalOldMotion() {
        val reports=mutableListOf<ByteArray>(); val mouse=HidMouse(reports::add)
        mouse.down(); mouse.move(0.9f,0.9f); mouse.cancel(); mouse.move(0.2f,0.2f)
        assertEquals(2,reports.size); assertArrayEquals(HidReports.mouse(0),reports.last())
    }
    @Test fun wheelAccumulatesSmallFingerMotion() {
        val reports=mutableListOf<ByteArray>(); val mouse=HidMouse(reports::add)
        repeat(6) { mouse.scroll(-8f) }; mouse.scroll(-1f)
        assertEquals(-1,reports.sumOf { it[3].toInt() })
    }
    @Test fun consumerUsagesMapHomeBackMediaAndVolume() {
        assertArrayEquals(byteArrayOf(0x23,0x02),HidReports.consumer(HidReports.consumerUsage(3)))
        assertEquals(0x224,HidReports.consumerUsage(4)); assertEquals(0xCD,HidReports.consumerUsage(85))
        assertEquals(0xE9,HidReports.consumerUsage(24)); assertEquals(0xEA,HidReports.consumerUsage(25))
    }
    @Test fun keyboardHoldsMultipleKeysAndLimitsSixKeyRollover() {
        val report=HidReports.keyboard((1..10).toList())
        assertEquals(8,report.size); assertArrayEquals(byteArrayOf(0,0,1,2,3,4,5,6),report)
        assertArrayEquals(ByteArray(8),HidReports.keyboard(emptyList()))
    }
    @Test fun capabilitiesSurviveProtocolRoundTrip() {
        val p=Packet(Kind.CAPABILITIES,code=Capability.NATIVE_MOUSE or Capability.TOUCH or Capability.PREVIEW,flag=false,text="Root · 触摸手势")
        val decoded=Wire.decode(Wire.encode(p))
        assertEquals(p.code,decoded.code); assertFalse(decoded.flag); assertEquals(p.text,decoded.text)
    }
}
