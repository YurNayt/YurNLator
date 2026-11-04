package com.winlator.cmod.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.session.MediaDecoder

private const val GST = "GStreamer"
private const val FFMPEG = "FFmpeg (winedmo)"
private const val DEFAULT = "Container default"

/**
 * Codecs: which backend Wine's Media Foundation plays cutscenes with, and software decoding
 * (see session/MediaDecoder). In a game's settings ([perGame]) each row can also follow the container.
 *
 * Values: decoder "gstreamer" | "ffmpeg" | "" (container default); software "1" | "0" | "".
 */
@Composable
internal fun CodecsCard(
    decoder: String, software: Boolean, onDecoder: (String) -> Unit, onSoftware: (Boolean) -> Unit,
) = CodecsRows(decoder, if (software) "1" else "0", false, onDecoder) { onSoftware(it == "1") }

@Composable
internal fun CodecsRows(decoder: String, software: String, perGame: Boolean, onDecoder: (String) -> Unit, onSoftware: (String) -> Unit) {
    SettingsCard {
        Text(
            "Cutscenes: GStreamer is the default. FFmpeg (winedmo) needs a newer arm64ec Proton build, otherwise " +
                "GStreamer is used. Software decoding keeps video frames off the GPU: try it for black video with sound.",
            Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val decoders = (if (perGame) listOf(DEFAULT) else emptyList()) + listOf(GST, FFMPEG)
        val decoderLabel = when (decoder) {
            MediaDecoder.BACKEND_FFMPEG -> FFMPEG
            MediaDecoder.BACKEND_GSTREAMER -> GST
            else -> if (perGame) DEFAULT else GST
        }
        SettingChoice("Builtin decoder", decoderLabel, decoders) {
            onDecoder(when (it) { FFMPEG -> MediaDecoder.BACKEND_FFMPEG; GST -> MediaDecoder.BACKEND_GSTREAMER; else -> "" })
        }
        SettingsDivider()
        val sw = (if (perGame) listOf(DEFAULT) else emptyList()) + listOf("Off", "On")
        val swLabel = when (software) { "1" -> "On"; "0" -> "Off"; else -> if (perGame) DEFAULT else "Off" }
        SettingChoice("Software decoding", swLabel, sw) {
            onSoftware(when (it) { "On" -> "1"; "Off" -> "0"; else -> "" })
        }
    }
}
