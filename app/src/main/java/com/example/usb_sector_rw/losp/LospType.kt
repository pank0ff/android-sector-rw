/**
 * *****************************************************************************
 * @author  Рундыгин Сергей
 * @date    2025-05-02
 * @version 1.0
 * @file    LospType.kt
 * @brief   Стандартные типы данных, используемые в проектах ЛОСП на Kotlin.
 * @details Типы определены для единообразия и читаемости при переносе кода с C/C++.
 * *****************************************************************************
 */

package com.example.usb_sector_rw.losp

// Общие указатели
typealias ptr  = Any?
typealias cptr = Any?

// Беззнаковые типы
typealias uint = UInt

// Знаковые типы
typealias i8   = Byte
typealias i16  = Short
typealias i32  = Int
typealias i64  = Long

// Знаковые типы для чтения
typealias ic8  = Byte
typealias ic16 = Short
typealias ic32 = Int
typealias ic64 = Long

// Беззнаковые типы
typealias u8   = UByte
typealias u16  = UShort
typealias u32  = UInt
typealias u64  = ULong

// Беззнаковые типы для чтения
typealias uc8  = UByte
typealias uc16 = UShort
typealias uc32 = UInt
typealias uc64 = ULong

// Логический тип
typealias bool = Boolean

// Символы и строки
typealias achar = Char
typealias wchar = Char
typealias astr  = String
typealias acstr = String
typealias wstr  = String
typealias wcstr = String
typealias tchar = Char
typealias tstr  = String
typealias tcstr = String

// Вещественные числа
typealias flt   = Float

// Константы
const val LOSP_FUNC_OK: UInt = 0u
const val TRUE: Boolean = true
const val FALSE: Boolean = false