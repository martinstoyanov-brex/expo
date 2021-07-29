// Copyright © 2021-present 650 Industries, Inc. (aka Expo)

#include "JPlayerData.h"

#include <jni.h>
#include <fbjni/fbjni.h>

#include <memory>
#include <string>

namespace expo {

using namespace facebook;
using namespace jni;

using TSelf = local_ref<HybridClass<expo::JPlayerData>::jhybriddata>;

TSelf JPlayerData::initHybrid(alias_ref<HybridClass::jhybridobject> jThis) {
    return makeCxxInstance(jThis);
}

void JPlayerData::registerNatives() {
    registerHybrid({
       makeNativeMethod("initHybrid", JPlayerData::initHybrid),
       makeNativeMethod("sampleBufferCallback", JPlayerData::sampleBufferCallback),
    });
}

void JPlayerData::sampleBufferCallback(jni::alias_ref<jni::JArrayByte> sampleBuffer, jdouble positionSeconds) {
    if (sampleBufferCallback_ == nullptr) {
        __android_log_write(ANDROID_LOG_WARN, TAG, "Sample Buffer Callback is null!");
        setEnableSampleBufferCallback(false);
        return;
    }

    auto sampleBufferStrong = make_local(sampleBuffer);
    try {
        sampleBufferCallback_(sampleBufferStrong, positionSeconds);
    } catch (const std::exception& exception) {
        auto message = "Sample Buffer Callback threw an error! " + std::string(exception.what());
        __android_log_write(ANDROID_LOG_ERROR, TAG, message.c_str());
    }
}

void JPlayerData::setEnableSampleBufferCallback(bool enable) {
    if (enable) {
        __android_log_write(ANDROID_LOG_INFO, TAG, "Enabling Sample Buffer Callback...");
    } else {
        __android_log_write(ANDROID_LOG_INFO, TAG, "Disabling Sample Buffer Callback...");
    }
    static const auto javaMethod = javaPart_->getClass()->getMethod<void(bool)>("setEnableSampleBufferCallback");
    javaMethod(javaPart_.get(), enable);
}

void JPlayerData::setSampleBufferCallback(const SampleBufferCallback &&sampleBufferCallback) {
    sampleBufferCallback_ = sampleBufferCallback;
    setEnableSampleBufferCallback(true);
}

void JPlayerData::unsetSampleBufferCallback() {
    sampleBufferCallback_ = nullptr;
    setEnableSampleBufferCallback(false);
}

} // namespace expo
