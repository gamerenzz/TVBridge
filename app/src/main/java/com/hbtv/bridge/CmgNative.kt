package com.hbtv.bridge

import android.content.Context
import java.nio.ByteBuffer

object CmgNative {

    init {
        try {
            System.loadLibrary("cmg_jni")
        } catch (e: Throwable) {
            LogManager.log("[CmgNative] 动态库加载失败: ${e.message}")
        }
    }

    private external fun nativeInit(wasmBytes: ByteArray?, ebBytes: ByteArray?, relocBytes: ByteArray?): Int
    private external fun nativeDecryptTs(directByteBuffer: ByteBuffer, length: Int): Int

    fun init(context: Context) {
        try {
            val wasmBytes = try { context.assets.open("cmg.wasm").readBytes() } catch (e: Exception) { null }
            val ebBytes = try { context.assets.open("sapi_cache/assets_2025_wasm_eb_prog.bin").readBytes() } catch (e: Exception) { null }
            val relocBytes = try { context.assets.open("sapi_cache/assets_2025_wasm_reloc_table.bin").readBytes() } catch (e: Exception) { null }

            nativeInit(wasmBytes, ebBytes, relocBytes)
            LogManager.log("[CmgNative] 原生 C 语言 TS 解密管线初始化完成")
        } catch (e: Exception) {
            LogManager.log("[CmgNative] 初始化异常: ${e.message}")
        }
    }

    fun decryptTsInPlace(directBuffer: ByteBuffer, length: Int): Int {
        return nativeDecryptTs(directBuffer, length)
    }
}
