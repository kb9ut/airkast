package com.example.airkast

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            val inputFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.JAPAN)
            val outputFormat = SimpleDateFormat("HH:mm", Locale.JAPAN)
            try {
                val start = inputFormat.parse(startTime)
                val end = inputFormat.parse(endTime)
                return "${outputFormat.format(start!!)} - ${outputFormat.format(end!!)}"
            } catch (e: Exception) {
                return "Time N/A"
            }
        }

    val formattedDisplayDate: String
        get() {
            val inputFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.JAPAN)
            val outputFormat = SimpleDateFormat("MM/dd", Locale.JAPAN)
            try {
                val start = inputFormat.parse(startTime)
                return outputFormat.format(start!!)
            } catch (e: Exception) {
                return ""
            }
        }

    val isDownloadable: Boolean
        get() {
            try {
                val inputFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.JAPAN)
                val endDate = inputFormat.parse(endTime)
                return endDate?.before(Date()) ?: false
            } catch (e: Exception) {
                return false
            }
        }

    val durationMs: Long
        get() {
            val inputFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.JAPAN)
            try {
                val start = inputFormat.parse(startTime)
                val end = inputFormat.parse(endTime)
                return (end?.time ?: 0) - (start?.time ?: 0)
            } catch (e: Exception) {
                return 0L
            }
        }
}
