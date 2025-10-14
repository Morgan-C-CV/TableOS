package com.tableos.settings

object ProjectionCardsBridge {
    private var loaded: Boolean = false
    init {
        try {
            System.loadLibrary("tableos_settings_native")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            // Native lib未加载时，仍然提供空实现避免崩溃
        }
    }


    fun detectNv21Safe(nv21: ByteArray, width: Int, height: Int, maxCards: Int): IntArray {
        return if (loaded) detectDecodeNv21(nv21, width, height, maxCards) else intArrayOf(0)
    }

    external fun detectDecodeNv21(nv21: ByteArray, width: Int, height: Int, maxCards: Int): IntArray
}