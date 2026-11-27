package com.aistudio.santrirank.mcdfbx.data

import androidx.room.TypeConverter
import java.util.Date

/** Converte entre Date e Long (epoch ms) para persistência no Room. */
object SantriTypeConverters {

    @TypeConverter
    fun dateToLong(date: Date?): Long? = date?.time

    @TypeConverter
    fun longToDate(millis: Long?): Date? = millis?.let { Date(it) }
}
