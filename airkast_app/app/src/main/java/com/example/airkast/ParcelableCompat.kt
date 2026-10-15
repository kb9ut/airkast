package com.example.airkast

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

/** API 33 の型付き getParcelableExtra と旧APIの差を吸収します。 */
internal inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

/** API 33 の型付き getParcelable と旧APIの差を吸収します。 */
internal inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
