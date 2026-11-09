package org.rombench.areas.memory

import android.os.StatFs
import org.rombench.core.Sys
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** /proc/meminfo values in kB. */
object MemInfo {
    fun read(): Map<String, Long> = Sys.read("/proc/meminfo")?.lineSequence()?.mapNotNull { l ->
        val i = l.indexOf(':'); if (i < 0) null else l.substring(0, i) to (l.substring(i + 1).trim().split(' ').firstOrNull()?.toLongOrNull() ?: return@mapNotNull null)
    }?.toMap() ?: emptyMap()
}

data class Zram(val name: String, val disksize: Long?, val algorithm: String?, val origData: Long?, val comprData: Long?, val memUsed: Long?)

object Swap {
    fun zram(): List<Zram> = Sys.list("/sys/block").filter { it.startsWith("zram") }.map { z ->
        val p = "/sys/block/$z"
        val mm = Sys.line("$p/mm_stat")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
        val algo = Sys.line("$p/comp_algorithm")?.let { Regex("""\[(\w+)]""").find(it)?.groupValues?.get(1) ?: it }
        Zram(z, Sys.long("$p/disksize"), algo, mm?.getOrNull(0), mm?.getOrNull(1), mm?.getOrNull(2))
    }

    fun swaps(): List<String> = Sys.read("/proc/swaps")?.lines()?.drop(1)?.filter { it.isNotBlank() } ?: emptyList()
}

data class Mount(val device: String, val point: String, val fs: String, val options: String)

object Mounts {
    fun read(): List<Mount> = Sys.read("/proc/mounts")?.lineSequence()?.mapNotNull { l ->
        val p = l.split(' '); if (p.size < 4) null else Mount(p[0], p[1], p[2], p[3])
    }?.toList() ?: emptyList()

    fun of(point: String) = read().lastOrNull { it.point == point }
}

data class Space(val path: String, val total: Long, val free: Long) {
    val freePct get() = if (total > 0) free * 100.0 / total else 0.0
}

fun space(path: String): Space? = runCatching { StatFs(path).let { Space(path, it.totalBytes, it.availableBytes) } }.getOrNull()

/** eMMC/UFS information from sysfs. */
object Flash {
    fun emmc(): Map<String, String> {
        val dev = "/sys/block/mmcblk0/device"
        if (!Sys.exists(dev)) return emptyMap()
        return listOf("name", "type", "manfid", "oemid", "date", "rev", "fwrev", "life_time", "pre_eol_info", "enhanced_area_size")
            .mapNotNull { a -> Sys.line("$dev/$a")?.let { a to it } }.toMap() +
            listOfNotNull(Sys.line("/sys/block/mmcblk0/size")?.toLongOrNull()?.let { "size" to "${it * 512 / 1_000_000_000} GB" }).toMap()
    }

    fun ufs(): Map<String, String> {
        val list = Sys.list("/sys/block").filter { it.startsWith("sd") }
        if (list.isEmpty()) return emptyMap()
        val dev = "/sys/block/${list.first()}/device"
        return listOf("vendor", "model", "rev").mapNotNull { a -> Sys.line("$dev/$a")?.let { "ufs.$a" to it } }.toMap()
    }

    /** pre_eol_info 0x01 normal, 0x02 warning (80% reserved used), 0x03 urgent. */
    fun eolLevel(v: String?) = v?.removePrefix("0x")?.toIntOrNull(16)
}

/** Simple file I/O benchmark in [dir]. Write is fsynced; read may hit the page cache. */
object IoBench {
    data class Result(val writeMBs: Double, val readMBs: Double, val randWriteIops: Double?, val sizeMb: Int)

    fun run(dir: File, sizeMb: Int, random: Boolean = true, cancelled: () -> Boolean = { false }): Result {
        val f = File(dir, "rombench-io.tmp")
        val buf = ByteArray(1 shl 20).also { Random(1).nextBytes(it) }
        try {
            val t0 = System.nanoTime()
            RandomAccessFile(f, "rw").use { raf ->
                repeat(sizeMb) { if (!cancelled()) raf.write(buf) }
                raf.fd.sync()
            }
            val tw = (System.nanoTime() - t0) / 1e9
            val t1 = System.nanoTime()
            var total = 0L
            f.inputStream().use { input -> while (true) { val n = input.read(buf); if (n <= 0 || cancelled()) break; total += n } }
            val tr = (System.nanoTime() - t1) / 1e9
            var iops: Double? = null
            if (random && !cancelled()) {
                val block = ByteArray(4096)
                val ops = 200
                val rnd = Random(2)
                val t2 = System.nanoTime()
                RandomAccessFile(f, "rw").use { raf ->
                    repeat(ops) {
                        raf.seek(rnd.nextLong(0, (sizeMb.toLong() shl 20) / 4096) * 4096)
                        raf.write(block)
                        raf.fd.sync()
                    }
                }
                iops = ops / ((System.nanoTime() - t2) / 1e9)
            }
            return Result(sizeMb / tw, total / 1048576.0 / tr, iops, sizeMb)
        } finally {
            f.delete()
        }
    }
}
