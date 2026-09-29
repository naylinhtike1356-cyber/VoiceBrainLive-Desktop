package com.example.voicebrainlive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.desktop.AccentMint
import com.example.voicebrainlive.desktop.BorderColor
import com.example.voicebrainlive.desktop.CardBg
import com.example.voicebrainlive.desktop.TextMain
import com.example.voicebrainlive.desktop.TextSub
import com.example.voicebrainlive.desktop.platform.phonemic.PhoneMicManager
import java.awt.image.BufferedImage

// Reuse the app's dark theme tokens from Main.kt (same package module).
// These are defined in Main.kt; we reference them directly.

/**
 * Settings card for the phone-as-mic feature.
 *
 * Shows an enable toggle, and when enabled: the QR code for the phone to
 * scan, the manual 6-digit PIN, the server address, and live status
 * (waiting / streaming with jitter-buffer stats).
 */
@Composable
fun PhoneMicCard(
    manager: PhoneMicManager,
    modifier: Modifier = Modifier,
) {
    val state by manager.state.collectAsState()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "📱 ဖုန်းကို မိုက်အဖြစ်သုံးရန်",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AccentMint,
                    )
                    Text(
                        "ဖုန်းဘရောက်ဇာကနေ မိုက်အသံကို Wi-Fi ကနေ တိုက်ရိုက်ပို့မယ်။",
                        color = TextSub,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
                Switch(
                    checked = manager.isEnabled(),
                    onCheckedChange = { on -> if (on) manager.enable() else manager.disable() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentMint,
                        checkedTrackColor = AccentMint.copy(alpha = 0.4f),
                    ),
                )
            }

            when (val s = state) {
                is PhoneMicManager.State.Disabled -> {
                    Text(
                        "ပိတ်ထားပါတယ်။ ဖွင့်လိုက်ရင် ဖုန်းနဲ့ ချိတ်ဆက်ဖို့ QR code ပြပေးမယ်။",
                        color = TextSub,
                        fontSize = 12.sp,
                    )
                }
                is PhoneMicManager.State.WaitingForPhone -> {
                    PhoneMicPairingView(manager, s.pin, s.lanIp, s.port)
                }
                is PhoneMicManager.State.Streaming -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("🟢", fontSize = 14.sp)
                        Column {
                            Text(
                                "ဖုန်းမိုက် ချိတ်ဆက်ထားပါတယ် (${s.clientIp})",
                                color = AccentMint,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(s.stats, color = TextSub, fontSize = 11.sp)
                        }
                    }
                    Text(
                        "ဖုန်း ချိတ်ဆက်မှု ပြတ်သွားရင် local mic ကို အလိုအလျောက် ပြန်ပြောင်းပေးမယ်။",
                        color = TextSub,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneMicPairingView(
    manager: PhoneMicManager,
    pin: String,
    lanIp: String,
    port: Int,
) {
    val qr = remember(manager.state.collectAsState().value) { manager.lastQrPayload() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // QR code
        val bitmap: ImageBitmap? = remember(qr) {
            qr?.second?.let { pixels ->
                val size = pixels.size
                val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until size) for (x in 0 until size) img.setRGB(x, y, pixels[y][x])
                img.toComposeImageBitmap()
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Phone pairing QR code",
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(8.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text("1. ဖုန်းကင်မရာနဲ့ QR ကို scan ဖတ်ပါ", color = TextMain, fontSize = 12.sp)
            Text("2. (သို့) PIN ရိုက်ထည့်ပါ:", color = TextMain, fontSize = 12.sp)
            Text(
                pin.chunked(3).joinToString(" "),
                color = AccentMint,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Text(
                "https://$lanIp:$port",
                color = TextSub,
                fontSize = 11.sp,
            )
            Text(
                "⚠️ ပထမအကြိမ် ဖွင့်ရင် browser က \"Not private\" လို့ ပြမယ် — Advanced → Proceed to site ကို နှိပ်ပေးပါ။",
                color = TextSub,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Text(
                "PIN အသစ်",
                color = AccentMint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { manager.regeneratePairing() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
