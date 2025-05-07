package com.example.usb_sector_rw.losp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Универсальный класс для работы с бинарными данными через единый буфер.
 * Поддерживает доступ к данным через делегаты для UInt, UShort и ByteArray.
 */
open class Union(private val size: u32) {

    /** Буфер, содержащий бинарные данные структуры. */
    protected val buffer: ByteBuffer = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Получить копию внутреннего массива байт.
     * @return Массив байт той же длины, что и буфер Union.
     */
    fun asByteArray(): ByteArray = buffer.array()

    /**
     * Загрузить бинарные данные в буфер union.
     * @param data Массив байт, размером точно соответствующий размеру буфера.
     * @throws IllegalArgumentException Если размер data не соответствует ожидаемому.
     */
    fun loadFrom(data: ByteArray) {
        require(data.size.toUInt() == size) { "Invalid size: ${data.size} != $size" }
        buffer.clear()
        buffer.put(data)
        buffer.flip()
    }

    /**
     * Делегат для доступа к UInt по указанному смещению.
     * @param offset Смещение в байтах относительно начала буфера.
     * @return Делегат для свойства типа UInt.
     */
    protected fun uIntAt(offset: Int): ReadWriteProperty<Any?, UInt> = object : ReadWriteProperty<Any?, UInt> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): UInt {
            return buffer.getInt(offset).toUInt()
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: UInt) {
            buffer.putInt(offset, value.toInt())
        }
    }

    /**
     * Делегат для доступа к UShort по указанному смещению.
     * @param offset Смещение в байтах относительно начала буфера.
     * @return Делегат для свойства типа UShort.
     */
    protected fun uShortAt(offset: Int): ReadWriteProperty<Any?, UShort> = object : ReadWriteProperty<Any?, UShort> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): UShort {
            return buffer.getShort(offset).toUShort()
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: UShort) {
            buffer.putShort(offset, value.toShort())
        }
    }

    /**
     * Делегат для доступа к массиву байт по указанному смещению и длине.
     * @param offset Смещение в байтах относительно начала буфера.
     * @param length Количество байт для чтения/записи.
     * @return Делегат для свойства типа ByteArray.
     */
    protected fun byteArrayAt(offset: Int, length: Int): ReadWriteProperty<Any?, ByteArray> = object : ReadWriteProperty<Any?, ByteArray> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): ByteArray {
            val pos = buffer.position()
            buffer.position(offset)
            val byteArray = ByteArray(length)
            buffer.get(byteArray)
            buffer.position(pos)
            return byteArray
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: ByteArray) {
            require(value.size == length) { "Invalid array size" }
            val pos = buffer.position()
            buffer.position(offset)
            buffer.put(value)
            buffer.position(pos)
        }
    }
}