package com.huydt.loto.loto_server.ui.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huydt.loto.loto_server.ui.components.*
import com.huydt.loto.loto_server.ui.theme.*
import com.huydt.loto.loto_server.viewmodel.LotoViewModel

// ─── Save helpers (reuse PREFS_NAME from ConnectScreen) ──────────────

private const val SETTINGS_PREFS = "loto_server_config"

private fun persistSetting(ctx: Context, key: String, value: Long) =
    ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit().putLong(key, value).apply()

private fun persistSetting(ctx: Context, key: String, value: Int) =
    ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit().putInt(key, value).apply()

private fun loadInt(ctx: Context, key: String, default: Int): Int =
    ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getInt(key, default)

private fun loadLong(ctx: Context, key: String, default: Long): Long =
    ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getLong(key, default)

private fun toast(ctx: Context, msg: String) =
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

// ─── Screen ──────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: LotoViewModel) {
    val context = LocalContext.current
    val room    by vm.roomState.collectAsState()

    // Load từ SharedPreferences — ưu tiên giá trị đang chạy trên server nếu có
    var priceInput     by remember { mutableStateOf(
        if (room.pricePerPage > 0) room.pricePerPage.toString()
        else loadLong(context, "price", 1_000L).toString()
    )}
    var speedInput     by remember { mutableStateOf(loadInt(context, "intervalMs", 3_000).toString()) }
    var autoResetInput by remember { mutableStateOf(
        if (room.autoResetDelaySec >= 0) room.autoResetDelaySec.toString()
        else loadInt(context, "autoResetSec", 10).toString()
    )}
    var autoStartInput by remember { mutableStateOf(
        if (room.autoStartDelaySec >= 0) room.autoStartDelaySec.toString()
        else loadInt(context, "autoStartSec", 0).toString()
    )}

    LaunchedEffect(room.pricePerPage)      { priceInput     = room.pricePerPage.toString() }
    LaunchedEffect(room.autoResetDelaySec) { autoResetInput = room.autoResetDelaySec.toString() }
    LaunchedEffect(room.autoStartDelaySec) { autoStartInput = room.autoStartDelaySec.toString() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cài đặt", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Thay đổi trực tiếp trong ván · tự động lưu", color = TextSecondary, fontSize = 13.sp)
            }
            // Saved indicator — small chip
            Box(
                modifier = Modifier
                    .background(GreenAccent.copy(0.1f), RoundedCornerShape(50))
                    .border(1.dp, GreenAccent.copy(0.3f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("💾 Tự lưu", color = GreenAccent, fontSize = 11.sp)
            }
        }

        // ─── Price per page ─────────────────────────────────────
        SectionCard("Giá mỗi tờ", Icons.Default.AttachMoney) {
            // Hiện tại
            Text(
                "Hiện tại: ${room.pricePerPage.let { "%,d đ".format(it).replace(',', '.') }}",
                color = GoldPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
            // Pending (đặt lúc đang chơi, áp dụng ván sau)
            if (room.pendingPricePerPage >= 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "⏳ Ván sau: ${"%,d đ".format(room.pendingPricePerPage).replace(',', '.')}",
                    color = OrangeAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            val locked = false // luôn cho phép đổi giá
            Text(
                when {
                    room.isActive -> "• Đang chơi: ✅ áp dụng từ ván sau"
                    room.players.any { !it.isBot && it.pageCount > 0 } ->
                        "• Đã có người mua: ✅ áp dụng từ ván sau"
                    else -> "• Chưa ai mua: ✅ áp dụng ngay"
                },
                color = TextDim, fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LotoTextField(
                    value         = priceInput,
                    onValueChange = { priceInput = it.filter { c -> c.isDigit() } },
                    label         = "Giá (đ)",
                    placeholder   = "10000",
                    modifier      = Modifier.weight(1f)
                )
                ActionButton(
                    text    = "Cập nhật",
                    onClick = {
                        priceInput.toLongOrNull()?.let { v ->
                            vm.setPrice(v)
                            persistSetting(context, "price", v)
                            toast(context, "✅ Đã cập nhật giá: %,d đ".format(v).replace(',', '.'))
                        }
                    },
                    color   = GoldPrimary,
                    enabled = priceInput.toLongOrNull() != null
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1000L, 5000L, 10000L, 20000L).forEach { preset ->
                    ActionButton(
                        text    = "%,dk".format(preset / 1000).replace(',', '.'),
                        onClick = {
                            priceInput = preset.toString()
                            vm.setPrice(preset)
                            persistSetting(context, "price", preset)
                        },
                        small   = true,
                        color   = BlueAccent
                    )
                }
            }
        }

        // ─── Draw speed ─────────────────────────────────────────
        SectionCard("Tốc độ rút số", Icons.Default.Speed) {
            Text(
                "Có thể thay đổi khi game đang chạy. Khi đang PAUSED sẽ áp dụng khi resume.",
                color = TextDim, fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LotoTextField(
                    value         = speedInput,
                    onValueChange = { speedInput = it.filter { c -> c.isDigit() } },
                    label         = "Interval (ms)",
                    placeholder   = "3000",
                    modifier      = Modifier.weight(1f)
                )
                ActionButton(
                    text    = "Áp dụng",
                    onClick = {
                        speedInput.toIntOrNull()?.let { v ->
                            vm.setDrawInterval(v)
                            persistSetting(context, "intervalMs", v)
                            toast(context, "✅ Tốc độ rút: ${v}ms")
                        }
                    },
                    color   = OrangeAccent,
                    enabled = speedInput.toIntOrNull()?.let { it >= 200 } ?: false
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mapOf("Nhanh" to "1000", "TB" to "3000", "Chậm" to "5000", "Rất chậm" to "8000")
                    .forEach { (label, ms) ->
                        ActionButton(
                            text    = label,
                            onClick = {
                                val v = ms.toInt()
                                speedInput = ms
                                vm.setDrawInterval(v)
                                persistSetting(context, "intervalMs", v)
                            },
                            small   = true,
                            color   = OrangeAccent
                        )
                    }
            }
        }

        // ─── Auto reset ─────────────────────────────────────────
        SectionCard("Tự động reset", Icons.Default.Refresh) {
            Text(
                "Hiện tại: " + if (room.autoResetDelaySec == 0) "TẮT" else "${room.autoResetDelaySec}s",
                color = GreenAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text("Thời gian (giây) sau khi game kết thúc để tự reset. 0 = tắt.", color = TextDim, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LotoTextField(
                    value         = autoResetInput,
                    onValueChange = { autoResetInput = it.filter { c -> c.isDigit() } },
                    label         = "Giây (0 = tắt)",
                    placeholder   = "30",
                    modifier      = Modifier.weight(1f)
                )
                ActionButton(
                    text    = "Lưu",
                    onClick = {
                        autoResetInput.toIntOrNull()?.let { v ->
                            vm.setAutoReset(v)
                            persistSetting(context, "autoResetSec", v)
                            toast(context, if (v == 0) "✅ Auto-reset: TẮT" else "✅ Auto-reset: ${v}s")
                        }
                    },
                    color   = GreenAccent,
                    enabled = autoResetInput.toIntOrNull() != null
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("0" to "Tắt", "10" to "10s", "30" to "30s", "60" to "1ph").forEach { (sec, label) ->
                    ActionButton(
                        text    = label,
                        onClick = {
                            val v = sec.toInt()
                            autoResetInput = sec
                            vm.setAutoReset(v)
                            persistSetting(context, "autoResetSec", v)
                        },
                        small   = true,
                        color   = GreenAccent
                    )
                }
            }
        }

        // ─── Auto start ──────────────────────────────────────────
        SectionCard("Tự động bắt đầu", Icons.Default.PlayCircle) {
            Text(
                "Hiện tại: " + if (room.autoStartDelaySec == 0) "TẮT" else "${room.autoStartDelaySec}s",
                color = BlueAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tự động bắt đầu game sau N giây khi có người thật mua tờ đầu tiên. 0 = tắt.",
                color = TextDim, fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LotoTextField(
                    value         = autoStartInput,
                    onValueChange = { autoStartInput = it.filter { c -> c.isDigit() } },
                    label         = "Giây (0 = tắt)",
                    placeholder   = "10",
                    modifier      = Modifier.weight(1f)
                )
                ActionButton(
                    text    = "Lưu",
                    onClick = {
                        autoStartInput.toIntOrNull()?.let { v ->
                            vm.setAutoStart(v)
                            persistSetting(context, "autoStartSec", v)
                            toast(context, if (v == 0) "✅ Auto-start: TẮT" else "✅ Auto-start: ${v}s")
                        }
                    },
                    color   = BlueAccent,
                    enabled = autoStartInput.toIntOrNull() != null
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("0" to "Tắt", "5" to "5s", "10" to "10s", "30" to "30s").forEach { (sec, label) ->
                    ActionButton(
                        text    = label,
                        onClick = {
                            val v = sec.toInt()
                            autoStartInput = sec
                            vm.setAutoStart(v)
                            persistSetting(context, "autoStartSec", v)
                        },
                        small   = true,
                        color   = BlueAccent
                    )
                }
            }
        }
    }
}
