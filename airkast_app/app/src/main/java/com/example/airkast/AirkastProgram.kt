package com.example.airkast

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** radikoが番組の開始・終了時刻に使うタイムスタンプ形式 (YYYYMMDDHHmmss)。 */
internal const val RADIKO_TIMESTAMP_PATTERN = "yyyyMMddHHmmss"

/** radikoのタイムスタンプ用の [SimpleDateFormat] を生成します（スレッドセーフでないため都度生成）。 */
internal fun radikoTimestampFormat(): SimpleDateFormat =
    SimpleDateFormat(RADIKO_TIMESTAMP_PATTERN, Locale.JAPAN)

@Parcelize
data class AirkastProgram(
    val id: String,
    val stationId: String,
    val startTime: String, // YYYYMMDDHHMMSS
    val endTime: String,   // YYYYMMDDHHMMSS
    var title: String,
    var description: String,
    var performer: String,
    var imageUrl: String?
) : Parcelable {

    val formattedDisplayTime: String
        get() {
            val outputFormat = SimpleDateFormat("HH:mm", Locale.JAPAN)
            return try {
                val inputFormat = radikoTimestampFormat()
                val start = inputFormat.parse(startTime)
                val end = inputFormat.parse(endTime)
                "${outputFormat.format(start!!)} - ${outputFormat.format(end!!)}"
            } catch (e: Exception) {
                "Time N/A"
            }
        }

    val formattedDisplayDate: String
        get() {
            return try {
                val start = radikoTimestampFormat().parse(startTime)
                SimpleDateFormat("MM/dd", Locale.JAPAN).format(start!!)
            } catch (e: Exception) {
                ""
            }
        }

    val isDownloadable: Boolean
        get() {
            return try {
                val endDate = radikoTimestampFormat().parse(endTime)
                endDate?.before(Date()) ?: false
            } catch (e: Exception) {
                false
            }
        }

    val durationMs: Long
        get() {
            return try {
                val inputFormat = radikoTimestampFormat()
                val start = inputFormat.parse(startTime)
                val end = inputFormat.parse(endTime)
                (end?.time ?: 0) - (start?.time ?: 0)
            } catch (e: Exception) {
                0L
            }
        }
}
