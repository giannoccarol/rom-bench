package org.rombench.areas.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmtBytes
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.LevelBar
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono

object MemoryArea : Area {
    override val id = "memory"
    override val title = R.string.memory_title
    override val subtitle = R.string.memory_subtitle
    override val icon: ImageVector = Icons.Outlined.Storage
    override val group = AreaGroup.SYSTEM
    override val autoSeconds = 8

    private fun kb(v: Long?) = v?.let { fmtBytes(it * 1024) } ?: "—"

    override suspend fun autoCheck(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TestResult>()
        val mi = MemInfo.read()
        val am = ctx.getSystemService(ActivityManager::class.java)
        val amInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val total = mi["MemTotal"]?.times(1024) ?: amInfo.totalMem
        val avail = mi["MemAvailable"]?.times(1024) ?: amInfo.availMem
        val pct = if (total > 0) avail * 100.0 / total else 0.0
        val ramDetails = listOf(
            d("MemTotal", kb(mi["MemTotal"])), d("MemAvailable", kb(mi["MemAvailable"])), d("MemFree", kb(mi["MemFree"])),
            d("Cached", kb(mi["Cached"])), d("SwapTotal", kb(mi["SwapTotal"])), d("SwapFree", kb(mi["SwapFree"])),
            d("CmaTotal", kb(mi["CmaTotal"])), d("ActivityManager threshold", fmtBytes(amInfo.threshold)),
            d("lowMemory", amInfo.lowMemory), d("isLowRamDevice", am.isLowRamDevice), d("memoryClass", "${am.memoryClass} MB / large ${am.largeMemoryClass} MB"),
        )
        out += if (pct < 10) result("ram", txt(R.string.memory_r_ram), Status.WARN, txt(R.string.memory_ram_summary, fmtBytes(total), fmtBytes(avail)), txt(R.string.memory_ram_low_why), ramDetails)
        else result("ram", txt(R.string.memory_r_ram), Status.PASS, txt(R.string.memory_ram_summary, fmtBytes(total), fmtBytes(avail)), details = ramDetails)

        // Swap / zram
        val zram = Swap.zram()
        val swaps = Swap.swaps()
        val swapTotal = mi["SwapTotal"] ?: 0
        val zDetails = zram.map { z -> d(z.name, "disksize ${z.disksize?.let { fmtBytes(it) } ?: "—"}, ${z.algorithm ?: "?"}, orig ${z.origData?.let { fmtBytes(it) } ?: "—"} → ${z.comprData?.let { fmtBytes(it) } ?: "—"}") } + swaps.map { d("/proc/swaps", it) }
        out += when {
            swapTotal == 0L && total < 6L * 1024 * 1024 * 1024 -> result("swap", txt(R.string.memory_r_swap), Status.WARN, txt(R.string.memory_swap_none), txt(R.string.memory_swap_none_why), zDetails)
            swapTotal == 0L -> result("swap", txt(R.string.memory_r_swap), Status.INFO, txt(R.string.memory_swap_none), details = zDetails)
            else -> result("swap", txt(R.string.memory_r_swap), Status.PASS, txt(R.string.memory_swap_summary, kb(swapTotal), zram.firstOrNull()?.algorithm ?: "swap"), details = zDetails)
        }

        // lmkd configuration
        val lmk = Sys.allProps().filterKeys { it.startsWith("ro.lmk.") || it.startsWith("persist.device_config.lmkd_native") || it == "sys.lmk.minfree_levels" }
        val psi = Sys.exists("/proc/pressure/memory")
        val usePsi = lmk["ro.lmk.use_psi"]?.let { it != "false" } ?: true
        val minfree = lmk["ro.lmk.use_minfree_levels"] == "true"
        val newStrategy = lmk["ro.lmk.use_new_strategy"] == "true"
        val lDetails = listOf(d("/proc/pressure/memory", if (psi) "present" else "absent"), d("init.svc.lmkd", Sys.prop("init.svc.lmkd"))) + lmk.map { (k, v) -> d(k, v) }
        out += when {
            Sys.prop("init.svc.lmkd").let { it.isNotBlank() && it != "running" } ->
                result("lmkd", txt(R.string.memory_r_lmkd), Status.FAIL, txt(R.string.memory_lmkd_stopped, Sys.prop("init.svc.lmkd")), txt(R.string.memory_lmkd_stopped_why), lDetails)
            !usePsi && minfree && !newStrategy ->
                result("lmkd", txt(R.string.memory_r_lmkd), Status.WARN, txt(R.string.memory_lmkd_combo), txt(R.string.memory_lmkd_combo_why), lDetails)
            usePsi && !psi && !minfree ->
                result("lmkd", txt(R.string.memory_r_lmkd), Status.WARN, txt(R.string.memory_lmkd_nopsi), txt(R.string.memory_lmkd_nopsi_why), lDetails)
            else -> result("lmkd", txt(R.string.memory_r_lmkd), Status.PASS, txt(if (usePsi) R.string.memory_lmkd_psi else R.string.memory_lmkd_minfree), details = lDetails)
        }

        // /data
        val data = space(Environment.getDataDirectory().path)
        val dataMount = Mounts.of("/data")
        val dDetails = listOf(d("/data", dataMount?.let { "${it.fs} ${it.device}" }), d("options", dataMount?.options?.take(120)), d("/system", space(Environment.getRootDirectory().path)?.let { "${fmtBytes(it.total)} (${fmtBytes(it.free)} free)" }), d("crypto", "${Sys.prop("ro.crypto.state")} ${Sys.prop("ro.crypto.type")}"))
        out += when {
            data == null -> result("data", txt(R.string.memory_r_data), Status.WARN, txt(R.string.memory_unreadable), null, dDetails)
            data.freePct < 10 -> result("data", txt(R.string.memory_r_data), Status.WARN, txt(R.string.memory_data_summary, fmtBytes(data.free), fmtBytes(data.total), dataMount?.fs ?: "?"), txt(R.string.memory_data_full_why), dDetails)
            else -> result("data", txt(R.string.memory_r_data), Status.PASS, txt(R.string.memory_data_summary, fmtBytes(data.free), fmtBytes(data.total), dataMount?.fs ?: "?"), details = dDetails)
        }

        // Flash chip
        val emmc = Flash.emmc() + Flash.ufs()
        val eol = Flash.eolLevel(emmc["pre_eol_info"])
        out += when {
            emmc.isEmpty() -> result("flash", txt(R.string.memory_r_flash), Status.INFO, txt(R.string.memory_unreadable), txt(R.string.memory_flash_unreadable_why))
            eol != null && eol >= 2 -> result("flash", txt(R.string.memory_r_flash), Status.WARN, raw(listOfNotNull(emmc["name"], emmc["size"], "pre_eol_info ${emmc["pre_eol_info"]}").joinToString(" · ")), txt(R.string.memory_flash_eol_why), emmc.map { (k, v) -> d(k, v) })
            else -> result("flash", txt(R.string.memory_r_flash), Status.INFO, raw(listOfNotNull(emmc["name"] ?: emmc["ufs.model"], emmc["size"], emmc["life_time"]?.let { "life $it" }).joinToString(" · ")), details = emmc.map { (k, v) -> d(k, v) })
        }

        // I/O speed
        val io = runCatching { IoBench.run(ctx.filesDir, 32) }.getOrNull()
        out += when {
            io == null -> result("io", txt(R.string.memory_r_io), Status.WARN, txt(R.string.memory_io_failed), txt(R.string.memory_io_failed_why))
            io.writeMBs < 5 -> result("io", txt(R.string.memory_r_io), Status.WARN, txt(R.string.memory_io_summary, fmt1(io.writeMBs), fmt1(io.readMBs), io.randWriteIops?.let { fmt1(it) } ?: "—"), txt(R.string.memory_io_slow_why), ioDetails(io))
            else -> result("io", txt(R.string.memory_r_io), Status.PASS, txt(R.string.memory_io_summary, fmt1(io.writeMBs), fmt1(io.readMBs), io.randWriteIops?.let { fmt1(it) } ?: "—"), details = ioDetails(io))
        }

        // Volumes (SD card, USB)
        val sm = ctx.getSystemService(StorageManager::class.java)
        val vols = sm.storageVolumes
        out += result(
            "volumes", txt(R.string.memory_r_volumes), Status.INFO,
            txt(R.string.memory_volumes_summary, vols.size, vols.count { it.isRemovable }),
            details = vols.map { v -> d(v.getDescription(ctx), "${v.state}${if (v.isRemovable) ", removable" else ""}${if (v.isPrimary) ", primary" else ""}") },
        )
        out
    }

    private fun ioDetails(io: IoBench.Result) = listOf(
        d("Size", "${io.sizeMb} MB in app data"), d("Sequential write + fsync", "${fmt1(io.writeMBs)} MB/s"),
        d("Sequential read (may be cached)", "${fmt1(io.readMBs)} MB/s"), d("Random 4K write + fsync", io.randWriteIops?.let { "${fmt1(it)} IOPS" }),
    )

    @Composable
    override fun Content() {
        RamCard()
        StorageCard()
        BenchCard()
        MountsCard()
    }

    @Composable
    private fun RamCard() {
        val mi = rememberPolled(1500) { MemInfo.read() }.value
        val zram = rememberPolled(3000) { Swap.zram() }.value
        SectionCard(stringResource(R.string.memory_sec_ram), icon = Icons.Outlined.Memory) {
            val total = mi?.get("MemTotal") ?: 0
            val avail = mi?.get("MemAvailable") ?: 0
            Row {
                Metric(kb(total), stringResource(R.string.memory_m_total), Modifier.weight(1f))
                Metric(kb(avail), stringResource(R.string.memory_m_available), Modifier.weight(1f))
                Metric(kb(mi?.get("SwapTotal")?.minus(mi["SwapFree"] ?: 0)), stringResource(R.string.memory_m_swap_used), Modifier.weight(1f))
            }
            LevelBar(if (total > 0) 1f - avail.toFloat() / total else 0f)
            zram?.forEach { z ->
                val ratio = if ((z.comprData ?: 0) > 0) (z.origData ?: 0).toDouble() / z.comprData!! else null
                Hint("${z.name}: ${z.algorithm ?: "?"}, ${z.disksize?.let { fmtBytes(it) } ?: "—"}, ${z.origData?.let { fmtBytes(it) } ?: "0"} → ${z.comprData?.let { fmtBytes(it) } ?: "0"}${ratio?.let { " (×${fmt1(it)})" } ?: ""}")
            }
        }
    }

    @Composable
    private fun StorageCard() {
        val ctx = LocalContext.current
        val spaces = rememberPolled(5000) {
            listOfNotNull(
                space(Environment.getDataDirectory().path), space(Environment.getRootDirectory().path),
                space("/vendor"), space(Environment.getExternalStorageDirectory().path),
            ) + ctx.getExternalFilesDirs(null).drop(1).filterNotNull().mapNotNull { space(it.path) }
        }.value
        val sc = LocalStatusColors.current
        SectionCard(stringResource(R.string.memory_sec_storage), icon = Icons.Outlined.SdStorage) {
            spaces?.distinctBy { it.total to it.free }?.forEach { s ->
                Column {
                    Row { Text(s.path, style = Mono, modifier = Modifier.weight(1f)); Text("${fmtBytes(s.free)} / ${fmtBytes(s.total)}", style = Mono) }
                    LevelBar(if (s.total > 0) 1f - s.free.toFloat() / s.total else 0f, color = if (s.freePct < 10) sc.warn else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    @Composable
    private fun BenchCard() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var size by rememberSaveable { mutableIntStateOf(128) }
        var job by remember { mutableStateOf<Job?>(null) }
        var res by remember { mutableStateOf<IoBench.Result?>(null) }
        var external by rememberSaveable { mutableStateOf(false) }
        val extDir = remember { ctx.getExternalFilesDirs(null).drop(1).firstOrNull() }
        SectionCard(stringResource(R.string.memory_sec_bench), icon = Icons.Outlined.Speed, subtitle = stringResource(R.string.memory_bench_help)) {
            ButtonRow {
                listOf(64, 128, 512).forEach { s -> FilterChip(size == s, { size = s }, enabled = job == null, label = { Text("$s MB") }) }
                if (extDir != null) FilterChip(external, { external = !external }, enabled = job == null, label = { Text(stringResource(R.string.memory_bench_sd)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job != null) {
                    OutlinedButton(onClick = { job?.cancel(); job = null }) { Text(stringResource(R.string.stop)) }
                    Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.width(20.dp))
                } else Button(onClick = {
                    job = scope.launch {
                        val dir = if (external && extDir != null) extDir else ctx.filesDir
                        val r = withContext(Dispatchers.IO) { runCatching { IoBench.run(dir, size, cancelled = { !isActive }) }.getOrNull() }
                        if (r != null && isActive) {
                            res = r
                            ResultStore.put(
                                result(
                                    if (external) "io_bench_sd" else "io_bench", txt(if (external) R.string.memory_r_io_sd else R.string.memory_r_io_long), Status.INFO,
                                    txt(R.string.memory_io_summary, fmt1(r.writeMBs), fmt1(r.readMBs), r.randWriteIops?.let { fmt1(it) } ?: "—"),
                                    details = ioDetails(r), kind = Kind.GUIDED,
                                ),
                            )
                        }
                        job = null
                    }
                }) { Text(stringResource(R.string.memory_bench_start)) }
            }
            res?.let { r ->
                Row {
                    Metric(fmt1(r.writeMBs), "MB/s write", Modifier.weight(1f))
                    Metric(fmt1(r.readMBs), "MB/s read", Modifier.weight(1f))
                    Metric(r.randWriteIops?.let { fmt1(it) } ?: "—", "4K IOPS", Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun MountsCard() {
        var all by rememberSaveable { mutableStateOf(false) }
        val mounts = rememberPolled(10_000) { Mounts.read() }.value
        val real = mounts?.filter { it.device.startsWith("/dev/block") || it.fs in setOf("ext4", "f2fs", "vfat", "exfat", "erofs", "sdcardfs", "fuse") }.orEmpty()
        SectionCard(stringResource(R.string.memory_sec_mounts), subtitle = stringResource(R.string.memory_mounts_count, real.size, mounts?.size ?: 0)) {
            (if (all) real else real.take(10)).forEach { m -> KeyValue(m.point, "${m.fs} ${m.device.substringAfterLast('/')}") }
            if (real.size > 10) OutlinedButton(onClick = { all = !all }) { Text(stringResource(if (all) R.string.memory_show_less else R.string.memory_show_all)) }
        }
    }
}
