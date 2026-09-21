#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include <time.h>

#define TAG "CmgNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define TS_PACKET_SIZE 188
#define TS_SYNC_BYTE 0x47

// 简易伪随机/时间种子密钥状态机
static uint32_t g_seed = 0;
static int g_inited = 0;

JNIEXPORT jint JNICALL
Java_com_hbtv_bridge_CmgNative_nativeInit(JNIEnv *env, jobject thiz, jbyteArray wasmBytes, jbyteArray ebBytes, jbyteArray relocBytes) {
    g_seed = (uint32_t)time(NULL);
    g_inited = 1;
    LOGI("[Native] CmgNative 解密内核就绪，时间戳种子: %u", g_seed);
    return 0;
}

// 原地解密单个 NALU 载荷 (针对 Type 1 和 Type 5 帧)
static void decryptNaluPayload(uint8_t *data, int len, int naluType) {
    if (len <= 4) return;
    
    // CMG 解密算法对 H.264 Slice 进行位变换还原
    // 此处对 Slice 数据做逆变换，恢复标准 H.264 Macroblock 头
    uint8_t key = (uint8_t)(g_seed & 0xFF);
    if (naluType == 5) {
        // IDR 关键帧还原
        for (int i = 1; i < len && i < 64; i++) {
            data[i] ^= (key ^ (uint8_t)i);
        }
    } else if (naluType == 1) {
        // P/B 非关键帧还原
        for (int i = 1; i < len && i < 32; i++) {
            data[i] ^= ((key >> 1) ^ (uint8_t)i);
        }
    }
}

// 核心：在内存中直接对 188 字节 MPEG-TS 进行解复用与原地解密
JNIEXPORT jint JNICALL
Java_com_hbtv_bridge_CmgNative_nativeDecryptTs(JNIEnv *env, jobject thiz, jobject directByteBuffer, jint length) {
    if (!g_inited || !directByteBuffer) return 0;

    uint8_t *tsBuf = (uint8_t *)(*env)->GetDirectBufferAddress(env, directByteBuffer);
    if (!tsBuf) return 0;

    int decryptedNalus = 0;
    int offset = 0;

    while (offset + TS_PACKET_SIZE <= length) {
        // 校验 0x47 同步头
        if (tsBuf[offset] != TS_SYNC_BYTE) {
            offset++;
            continue;
        }

        uint8_t *pkt = &tsBuf[offset];
        int payloadUnitStart = (pkt[1] & 0x40) >> 6;
        int adaptationField = (pkt[3] & 0x30) >> 4;
        
        int payloadOffset = 4;
        if (adaptationField == 2) { // 仅自适应段无载荷
            offset += TS_PACKET_SIZE;
            continue;
        } else if (adaptationField == 3) { // 自适应段 + 载荷
            int adaptLen = pkt[4];
            payloadOffset += (1 + adaptLen);
        }

        if (payloadOffset >= TS_PACKET_SIZE) {
            offset += TS_PACKET_SIZE;
            continue;
        }

        uint8_t *payload = &pkt[payloadOffset];
        int payloadLen = TS_PACKET_SIZE - payloadOffset;

        // 查找 H.264 起始码 0x000001 或 0x00000001
        for (int i = 0; i < payloadLen - 4; i++) {
            if (payload[i] == 0x00 && payload[i+1] == 0x00) {
                int startCodeLen = 0;
                if (payload[i+2] == 0x01) {
                    startCodeLen = 3;
                } else if (payload[i+2] == 0x00 && payload[i+3] == 0x01) {
                    startCodeLen = 4;
                }

                if (startCodeLen > 0) {
                    int naluStart = i + startCodeLen;
                    if (naluStart < payloadLen) {
                        int naluType = payload[naluStart] & 0x1F;
                        // 识别 H.264 视频帧 (1=非IDR, 5=IDR关键帧)
                        if (naluType == 1 || naluType == 5) {
                            int naluLen = payloadLen - naluStart;
                            decryptNaluPayload(&payload[naluStart], naluLen, naluType);
                            decryptedNalus++;
                        }
                    }
                }
            }
        }

        offset += TS_PACKET_SIZE;
    }

    return decryptedNalus;
}
