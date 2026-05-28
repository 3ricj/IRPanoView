#include "hik_libusb_session.h"

#include "hik_libusb_globals.h"

#include <jni.h>

#include <android/log.h>

#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HikNativeUsb", __VA_ARGS__)

namespace {

std::mutex g_mapMutex;
std::unordered_map<jlong, std::unique_ptr<hik::Session>> g_sessions;

hik::Session* GetSession(jlong handle) {
    std::lock_guard<std::mutex> lock(g_mapMutex);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) {
        return nullptr;
    }
    return it->second.get();
}

jlong StoreSession(std::unique_ptr<hik::Session> session) {
    std::lock_guard<std::mutex> lock(g_mapMutex);
    static jlong nextId = 1;
    const jlong id = nextId++;
    g_sessions[id] = std::move(session);
    return id;
}

void RemoveSession(jlong handle) {
    std::lock_guard<std::mutex> lock(g_mapMutex);
    g_sessions.erase(handle);
}

jbyteArray ToJbyteArray(JNIEnv* env, const uint8_t* data, jsize len) {
    jbyteArray arr = env->NewByteArray(len);
    if (arr != nullptr && len > 0) {
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
    }
    return arr;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeInit(JNIEnv*, jclass) {
    return hik::ensureLibusbInit() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeOpen(JNIEnv*, jclass, jint fd) {
    auto session = std::make_unique<hik::Session>();
    if (!session->Open(fd)) {
        return 0;
    }
    return StoreSession(std::move(session));
}

JNIEXPORT jint JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeClaimInterface(JNIEnv*, jclass, jlong handle, jint iface) {
    hik::Session* session = GetSession(handle);
    return session != nullptr ? session->ClaimInterface(static_cast<int>(iface)) : -1;
}

JNIEXPORT jint JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeSetInterfaceAlt(
    JNIEnv*, jclass, jlong handle, jint iface, jint alt) {
    hik::Session* session = GetSession(handle);
    return session != nullptr ? session->SetInterfaceAlt(static_cast<int>(iface), static_cast<int>(alt)) : -1;
}

JNIEXPORT jint JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeControlWrite(
    JNIEnv* env, jclass, jlong handle, jint bm, jint bReq, jint wValue, jint wIndex, jbyteArray data) {
    hik::Session* session = GetSession(handle);
    if (session == nullptr || data == nullptr) {
        return -1;
    }
    const jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return -1;
    }
    const int rc = session->ControlWrite(
        static_cast<uint8_t>(bm),
        static_cast<uint8_t>(bReq),
        static_cast<uint16_t>(wValue),
        static_cast<uint16_t>(wIndex),
        reinterpret_cast<uint8_t*>(bytes),
        static_cast<int>(len));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return rc;
}

JNIEXPORT jbyteArray JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeControlRead(
    JNIEnv* env, jclass, jlong handle, jint bm, jint bReq, jint wValue, jint wIndex, jint length) {
    hik::Session* session = GetSession(handle);
    if (session == nullptr || length <= 0) {
        return nullptr;
    }
    std::vector<uint8_t> buf(static_cast<size_t>(length));
    const int rc = session->ControlRead(
        static_cast<uint8_t>(bm),
        static_cast<uint8_t>(bReq),
        static_cast<uint16_t>(wValue),
        static_cast<uint16_t>(wIndex),
        buf.data(),
        static_cast<int>(length));
    if (rc < 0) {
        return nullptr;
    }
    return ToJbyteArray(env, buf.data(), rc);
}

JNIEXPORT jint JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeStartBulkIn(
    JNIEnv*, jclass, jlong handle, jint ep, jint packetSize) {
    hik::Session* session = GetSession(handle);
    return session != nullptr
        ? session->StartBulkIn(static_cast<uint8_t>(ep), static_cast<int>(packetSize))
        : -1;
}

JNIEXPORT jbyteArray JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativePollBulkChunk(
    JNIEnv* env, jclass, jlong handle, jint maxLen, jint timeoutMs) {
    hik::Session* session = GetSession(handle);
    if (session == nullptr || maxLen <= 0) {
        return nullptr;
    }
    std::vector<uint8_t> buf(static_cast<size_t>(maxLen));
    const int rc = session->PollBulkChunk(buf.data(), static_cast<int>(maxLen), static_cast<int>(timeoutMs));
    if (rc <= 0) {
        return nullptr;
    }
    return ToJbyteArray(env, buf.data(), rc);
}

JNIEXPORT jint JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeCancelBulkIn(JNIEnv*, jclass, jlong handle) {
    hik::Session* session = GetSession(handle);
    if (session == nullptr) {
        return 0;
    }
    const hik::BulkCancelResult result = session->CancelBulkIn();
    return result.cancelCalled;
}

JNIEXPORT jintArray JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeCancelBulkInDetail(
    JNIEnv* env, jclass, jlong handle, jint maxWaitMs) {
    jintArray arr = env->NewIntArray(3);
    if (arr == nullptr) {
        return nullptr;
    }
    jint values[3] = {0, 0, 0};
    hik::Session* session = GetSession(handle);
    if (session != nullptr) {
        const hik::BulkCancelResult result = session->CancelBulkIn(static_cast<int>(maxWaitMs));
        values[0] = result.cancelCalled;
        values[1] = static_cast<jint>(result.elapsedMs);
        values[2] = result.inFlightAfter ? 1 : 0;
    }
    env->SetIntArrayRegion(arr, 0, 3, values);
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeReleaseInterface(
    JNIEnv*, jclass, jlong handle, jint iface) {
    hik::Session* session = GetSession(handle);
    return session != nullptr ? session->ReleaseInterface(static_cast<int>(iface)) : -1;
}

JNIEXPORT jlong JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeStopChannelSoft(
    JNIEnv*, jclass, jlong handle, jint vsIface) {
    hik::Session* session = GetSession(handle);
    if (session == nullptr) {
        return -1;
    }
    const hik::StopResult result = session->StopChannelSoft(static_cast<int>(vsIface));
    return result.elapsedMs;
}

JNIEXPORT jlong JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeStopChannelFull(
    JNIEnv*, jclass, jlong handle, jint vsIface) {
    hik::Session* session = GetSession(handle);
    if (session == nullptr) {
        return -1;
    }
    const hik::StopResult result = session->StopChannelFull(static_cast<int>(vsIface));
    return result.elapsedMs;
}

JNIEXPORT jlong JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeStopChannelReference(
    JNIEnv* env, jclass, jlong handle, jint vsIface, jintArray ifacesArr) {
    hik::Session* session = GetSession(handle);
    if (session == nullptr) {
        return -1;
    }
    std::vector<int> ifaces;
    if (ifacesArr != nullptr) {
        const jsize len = env->GetArrayLength(ifacesArr);
        if (len > 0) {
            ifaces.resize(static_cast<size_t>(len));
            env->GetIntArrayRegion(ifacesArr, 0, len, ifaces.data());
        }
    }
    const hik::StopResult result = session->StopChannelReference(
        static_cast<int>(vsIface),
        ifaces.empty() ? nullptr : ifaces.data(),
        static_cast<int>(ifaces.size()));
    return result.elapsedMs;
}

JNIEXPORT void JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeCloseHandle(
    JNIEnv* env, jclass, jlong handle, jint vsIface, jintArray ifacesArr) {
    hik::Session* session = GetSession(handle);
    if (session != nullptr) {
        std::vector<int> ifaces;
        if (ifacesArr != nullptr) {
            const jsize len = env->GetArrayLength(ifacesArr);
            if (len > 0) {
                ifaces.resize(static_cast<size_t>(len));
                env->GetIntArrayRegion(ifacesArr, 0, len, ifaces.data());
            }
        }
        session->CloseHandle(
            static_cast<int>(vsIface),
            ifaces.empty() ? nullptr : ifaces.data(),
            static_cast<int>(ifaces.size()));
    }
    RemoveSession(handle);
}

JNIEXPORT void JNICALL
Java_com_vilos_irpanoview_camera_hik_HikNativeUsb_nativeClose(JNIEnv*, jclass, jlong handle) {
    hik::Session* session = GetSession(handle);
    if (session != nullptr) {
        session->Close();
    }
    RemoveSession(handle);
}

}  // extern "C"
