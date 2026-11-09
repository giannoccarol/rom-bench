package org.rombench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rombench.areas.battery.looksLikeMilliamps
import org.rombench.areas.cpu.CpuInfo
import org.rombench.areas.device.DeviceArea
import org.rombench.core.Detail
import org.rombench.core.Status
import org.rombench.report.Compare
import org.rombench.report.Formats
import org.rombench.report.Report
import org.rombench.report.ReportFormat

class LogicTest {
    @Test
    fun cpuRanges() {
        assertEquals(listOf(0, 1, 2, 3, 6), CpuInfo.range("0-3,6"))
        assertEquals(listOf(0), CpuInfo.range("0"))
        assertEquals(emptyList<Int>(), CpuInfo.range("garbage-"))
        assertEquals(emptyList<Int>(), CpuInfo.range(null))
    }

    @Test
    fun cpuParts() {
        assertEquals("Cortex-A57", CpuInfo.partName("0xd07"))
        assertEquals("Krait", CpuInfo.partName("0x06f"))
    }

    @Test
    fun kernelVersions() {
        assertEquals(3 to 10, DeviceArea.kernelVersion("3.10.108-lineage-g1234"))
        assertEquals(5 to 15, DeviceArea.minKernelFor(36))
    }

    @Test
    fun batteryUnits() {
        assertTrue(looksLikeMilliamps(-450))
        assertFalse(looksLikeMilliamps(-450_000))
        assertFalse(looksLikeMilliamps(0))
    }

    private fun report(vararg items: Pair<String, Status>) = Report(
        1L, "test", listOf(Detail("Device", "cityman")),
        listOf(Report.AreaReport("wifi", "Wi-Fi", null, items.map { (k, s) -> Report.Item(k, k, s, "auto", "s-$s", null, emptyList()) })),
    )

    @Test
    fun compareFindsRegressions() {
        val old = report("wifi.scan" to Status.PASS, "wifi.ping" to Status.FAIL, "wifi.gone" to Status.PASS)
        val new = report("wifi.scan" to Status.FAIL, "wifi.ping" to Status.PASS, "wifi.new" to Status.INFO)
        val rows = Compare.diff(old, new).associateBy { it.key }
        assertEquals(Compare.Change.REGRESSION, rows["wifi.scan"]!!.change)
        assertEquals(Compare.Change.IMPROVEMENT, rows["wifi.ping"]!!.change)
        assertEquals(Compare.Change.ONLY_OLD, rows["wifi.gone"]!!.change)
        assertEquals(Compare.Change.ONLY_NEW, rows["wifi.new"]!!.change)
    }

    @Test
    fun textReportHasCountsAndMarks() {
        val text = Formats.render(report("wifi.scan" to Status.FAIL), ReportFormat.TEXT)
        assertTrue(text.contains("[FAIL] wifi.scan"))
        assertTrue(text.contains("Fail: 1"))
        val bb = Formats.render(report("wifi.scan" to Status.PASS), ReportFormat.BBCODE)
        assertTrue(bb.contains("[LIST]"))
    }
}
