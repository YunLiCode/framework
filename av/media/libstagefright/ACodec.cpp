/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef ANDROID_DEFAULT_CODE
    #define LOG_TAG "ACodec"
    #include <cutils/xlog.h>
    #undef LOGE
    #undef LOGW
    #undef LOGI
    #undef LOGD
    #undef LOGV
    #define LOGE XLOGE
    #define LOGW XLOGW
    #define LOGI XLOGI
    #define LOGD XLOGD
    #define LOGV XLOGV
#else
//    #define LOG_NDEBUG 0
    #define LOG_TAG "ACodec"
    #include <utils/Log.h>
#endif

#include <media/stagefright/ACodec.h>

#include <binder/MemoryDealer.h>

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>

#include <media/stagefright/MediaCodecList.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/NativeWindowWrapper.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>

#include <media/hardware/HardwareAPI.h>

#include <OMX_Component.h>

#include "include/avc_utils.h"

#ifndef ANDROID_DEFAULT_CODE
#define DUMP_PROFILE 0
#include <ctype.h>

//#define DUMP_BITSTREAM
#define ENABLE_MTK_BUF_ADDR_ALIGNMENT
#define MTK_BUF_ADDR_ALIGNMENT_VALUE 512

#endif

namespace android {

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

struct CodecObserver : public BnOMXObserver {
    CodecObserver() {}

    void setNotificationMessage(const sp<AMessage> &msg) {
        mNotify = msg;
    }

    // from IOMXObserver
    virtual void onMessage(const omx_message &omx_msg) {
        sp<AMessage> msg = mNotify->dup();

        msg->setInt32("type", omx_msg.type);
        msg->setPointer("node", omx_msg.node);

        switch (omx_msg.type) {
            case omx_message::EVENT:
            {
                msg->setInt32("event", omx_msg.u.event_data.event);
                msg->setInt32("data1", omx_msg.u.event_data.data1);
                msg->setInt32("data2", omx_msg.u.event_data.data2);
                break;
            }

            case omx_message::EMPTY_BUFFER_DONE:
            {
                msg->setPointer("buffer", omx_msg.u.buffer_data.buffer);
                break;
            }

            case omx_message::FILL_BUFFER_DONE:
            {
                msg->setPointer(
                        "buffer", omx_msg.u.extended_buffer_data.buffer);
                msg->setInt32(
                        "range_offset",
                        omx_msg.u.extended_buffer_data.range_offset);
                msg->setInt32(
                        "range_length",
                        omx_msg.u.extended_buffer_data.range_length);
                msg->setInt32(
                        "flags",
                        omx_msg.u.extended_buffer_data.flags);
                msg->setInt64(
                        "timestamp",
                        omx_msg.u.extended_buffer_data.timestamp);
                msg->setPointer(
                        "platform_private",
                        omx_msg.u.extended_buffer_data.platform_private);
                msg->setPointer(
                        "data_ptr",
                        omx_msg.u.extended_buffer_data.data_ptr);
                break;
            }

            default:
                TRESPASS();
                break;
        }

        msg->post();
    }

protected:
    virtual ~CodecObserver() {}

private:
    sp<AMessage> mNotify;

    DISALLOW_EVIL_CONSTRUCTORS(CodecObserver);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::BaseState : public AState {
    BaseState(ACodec *codec, const sp<AState> &parentState = NULL);

protected:
    enum PortMode {
        KEEP_BUFFERS,
        RESUBMIT_BUFFERS,
        FREE_BUFFERS,
    };

    ACodec *mCodec;

    virtual PortMode getPortMode(OMX_U32 portIndex);

    virtual bool onMessageReceived(const sp<AMessage> &msg);

    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);

    virtual void onOutputBufferDrained(const sp<AMessage> &msg);
    virtual void onInputBufferFilled(const sp<AMessage> &msg);

    void postFillThisBuffer(BufferInfo *info);

private:
    bool onOMXMessage(const sp<AMessage> &msg);

    bool onOMXEmptyBufferDone(IOMX::buffer_id bufferID);

    bool onOMXFillBufferDone(
            IOMX::buffer_id bufferID,
            size_t rangeOffset, size_t rangeLength,
            OMX_U32 flags,
            int64_t timeUs,
            void *platformPrivate,
            void *dataPtr);
#ifndef ANDROID_DEFAULT_CODE
    static void dumpProfile(const char* tag, const char* szName, int64_t timeUs);
    static bool isAudio(const char* strName);
#endif

    void getMoreInputDataIfPossible();

    DISALLOW_EVIL_CONSTRUCTORS(BaseState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::UninitializedState : public ACodec::BaseState {
    UninitializedState(ACodec *codec);

protected:
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual void stateEntered();

private:
    void onSetup(const sp<AMessage> &msg);
    bool onAllocateComponent(const sp<AMessage> &msg);

    DISALLOW_EVIL_CONSTRUCTORS(UninitializedState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::LoadedState : public ACodec::BaseState {
    LoadedState(ACodec *codec);

protected:
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual void stateEntered();

private:
    friend struct ACodec::UninitializedState;

    bool onConfigureComponent(const sp<AMessage> &msg);
    void onStart();
    void onShutdown(bool keepComponentAllocated);

    DISALLOW_EVIL_CONSTRUCTORS(LoadedState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::LoadedToIdleState : public ACodec::BaseState {
    LoadedToIdleState(ACodec *codec);

protected:
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);
    virtual void stateEntered();

private:
    status_t allocateBuffers();

    DISALLOW_EVIL_CONSTRUCTORS(LoadedToIdleState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::IdleToExecutingState : public ACodec::BaseState {
    IdleToExecutingState(ACodec *codec);

protected:
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);
    virtual void stateEntered();

private:
    DISALLOW_EVIL_CONSTRUCTORS(IdleToExecutingState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::ExecutingState : public ACodec::BaseState {
    ExecutingState(ACodec *codec);

    void submitOutputBuffers();

    // Submit output buffers to the decoder, submit input buffers to client
    // to fill with data.
    void resume();

    // Returns true iff input and output buffers are in play.
    bool active() const { return mActive; }

protected:
    virtual PortMode getPortMode(OMX_U32 portIndex);
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual void stateEntered();

    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);

private:
    bool mActive;

    DISALLOW_EVIL_CONSTRUCTORS(ExecutingState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::OutputPortSettingsChangedState : public ACodec::BaseState {
    OutputPortSettingsChangedState(ACodec *codec);

protected:
    virtual PortMode getPortMode(OMX_U32 portIndex);
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual void stateEntered();

    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);

private:
    DISALLOW_EVIL_CONSTRUCTORS(OutputPortSettingsChangedState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::ExecutingToIdleState : public ACodec::BaseState {
    ExecutingToIdleState(ACodec *codec);

protected:
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual void stateEntered();

    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);

    virtual void onOutputBufferDrained(const sp<AMessage> &msg);
    virtual void onInputBufferFilled(const sp<AMessage> &msg);

private:
    void changeStateIfWeOwnAllBuffers();

    bool mComponentNowIdle;

    DISALLOW_EVIL_CONSTRUCTORS(ExecutingToIdleState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::IdleToLoadedState : public ACodec::BaseState {
    IdleToLoadedState(ACodec *codec);

protected:
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual void stateEntered();

    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);

private:
    DISALLOW_EVIL_CONSTRUCTORS(IdleToLoadedState);
};

////////////////////////////////////////////////////////////////////////////////

struct ACodec::FlushingState : public ACodec::BaseState {
    FlushingState(ACodec *codec);

protected:
    virtual bool onMessageReceived(const sp<AMessage> &msg);
    virtual void stateEntered();

    virtual bool onOMXEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);

    virtual void onOutputBufferDrained(const sp<AMessage> &msg);
    virtual void onInputBufferFilled(const sp<AMessage> &msg);

private:
    bool mFlushComplete[2];

    void changeStateIfWeOwnAllBuffers();

    DISALLOW_EVIL_CONSTRUCTORS(FlushingState);
};

////////////////////////////////////////////////////////////////////////////////

ACodec::ACodec()
    : mQuirks(0),
      mNode(NULL),
#ifndef ANDROID_DEFAULT_CODE
      mSupportsPartialFrames(false),
      mLeftOverBuffer(NULL),
      mMaxQueueBufferNum(-1),
      mDumpFile(NULL),
      mIsVideoDecoder(false),
      mIsVideoEncoder(false),
#endif
      mSentFormat(false),
      mIsEncoder(false),
      mShutdownInProgress(false),
      mEncoderDelay(0),
      mEncoderPadding(0),
      mChannelMaskPresent(false),
      mChannelMask(0) {
    mUninitializedState = new UninitializedState(this);
    mLoadedState = new LoadedState(this);
    mLoadedToIdleState = new LoadedToIdleState(this);
    mIdleToExecutingState = new IdleToExecutingState(this);
    mExecutingState = new ExecutingState(this);

    mOutputPortSettingsChangedState =
        new OutputPortSettingsChangedState(this);

    mExecutingToIdleState = new ExecutingToIdleState(this);
    mIdleToLoadedState = new IdleToLoadedState(this);
    mFlushingState = new FlushingState(this);

    mPortEOS[kPortIndexInput] = mPortEOS[kPortIndexOutput] = false;
    mInputEOSResult = OK;

    changeState(mUninitializedState);

}

ACodec::~ACodec() {
    ALOGD("~ACodec");
#ifndef ANDROID_DEFAULT_CODE    
   if (mDumpFile != NULL) {
        fclose(mDumpFile);
        mDumpFile = NULL;
        ALOGD("dump file closed");
    }
#endif
}

void ACodec::setNotificationMessage(const sp<AMessage> &msg) {
    mNotify = msg;
}

void ACodec::initiateSetup(const sp<AMessage> &msg) {
    msg->setWhat(kWhatSetup);
    msg->setTarget(id());
    msg->post();
}


void ACodec::initiateAllocateComponent(const sp<AMessage> &msg) {
    msg->setWhat(kWhatAllocateComponent);
    msg->setTarget(id());
    msg->post();
}

void ACodec::initiateConfigureComponent(const sp<AMessage> &msg) {
    msg->setWhat(kWhatConfigureComponent);
    msg->setTarget(id());
    msg->post();
}

void ACodec::initiateStart() {
    (new AMessage(kWhatStart, id()))->post();
}

void ACodec::signalFlush() {
    ALOGV("[%s] signalFlush", mComponentName.c_str());
    (new AMessage(kWhatFlush, id()))->post();
}

void ACodec::signalResume() {
    (new AMessage(kWhatResume, id()))->post();
}

void ACodec::initiateShutdown(bool keepComponentAllocated) {
    sp<AMessage> msg = new AMessage(kWhatShutdown, id());
    msg->setInt32("keepComponentAllocated", keepComponentAllocated);
    msg->post();
}


void ACodec::signalRequestIDRFrame() {
    (new AMessage(kWhatRequestIDRFrame, id()))->post();
}

#ifndef ANDROID_DEFAULT_CODE
void ACodec::signalVEncIInterval(int seconds) {
    sp<AMessage> msg = new AMessage(kWhatMtkVEncIFrameInterval, id());
    msg->setInt32("MtkVEncIRate", seconds);
    msg->post();
}
#endif//ANDROID_DEFAULT_CODE

status_t ACodec::allocateBuffersOnPort(OMX_U32 portIndex) {
    CHECK(portIndex == kPortIndexInput || portIndex == kPortIndexOutput);

    CHECK(mDealer[portIndex] == NULL);
    CHECK(mBuffers[portIndex].isEmpty());

    status_t err;
    if (mNativeWindow != NULL && portIndex == kPortIndexOutput) {
        err = allocateOutputBuffersFromNativeWindow();
    } else {
        OMX_PARAM_PORTDEFINITIONTYPE def;
        InitOMXParams(&def);
        def.nPortIndex = portIndex;

        err = mOMX->getParameter(
                mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

        if (err == OK) {
            ALOGV("[%s] Allocating %lu buffers of size %lu on %s port",
                    mComponentName.c_str(),
                    def.nBufferCountActual, def.nBufferSize,
                    portIndex == kPortIndexInput ? "input" : "output");
#ifndef ANDROID_DEFAULT_CODE
            size_t totalSize;
            //TODO: get alignment info from omx
            totalSize = def.nBufferCountActual * (((def.nBufferSize + MTK_BUF_ADDR_ALIGNMENT_VALUE-1) & ~(MTK_BUF_ADDR_ALIGNMENT_VALUE-1)) + MTK_BUF_ADDR_ALIGNMENT_VALUE);

#else
            size_t totalSize = def.nBufferCountActual * def.nBufferSize;
#endif
            mDealer[portIndex] = new MemoryDealer(totalSize, "ACodec");

            for (OMX_U32 i = 0; i < def.nBufferCountActual; ++i) {
                sp<IMemory> mem = mDealer[portIndex]->allocate(def.nBufferSize);

                //        CHECK(mem.get() != NULL);
                if (mem.get() == NULL) {
                    ALOGE("[%s] cannot allocate %s port(i=%d) buffer(%lu)", 
                            mComponentName.c_str(), portIndex == kPortIndexInput ? "input" : "output",
                            i, def.nBufferSize);
                    return NO_MEMORY;
                }

                BufferInfo info;
                info.mStatus = BufferInfo::OWNED_BY_US;

                uint32_t requiresAllocateBufferBit =
                    (portIndex == kPortIndexInput)
                        ? OMXCodec::kRequiresAllocateBufferOnInputPorts
                        : OMXCodec::kRequiresAllocateBufferOnOutputPorts;

                if (portIndex == kPortIndexInput && (mFlags & kFlagIsSecure)) {
                    mem.clear();

                    void *ptr;
                    err = mOMX->allocateBuffer(
                            mNode, portIndex, def.nBufferSize, &info.mBufferID,
                            &ptr);

                    info.mData = new ABuffer(ptr, def.nBufferSize);
                } else if (mQuirks & requiresAllocateBufferBit) {
                    err = mOMX->allocateBufferWithBackup(
                            mNode, portIndex, mem, &info.mBufferID);
                } else {
                    err = mOMX->useBuffer(mNode, portIndex, mem, &info.mBufferID);
                }

                if (mem != NULL) {
#ifndef ANDROID_DEFAULT_CODE
                    //TODO: get alignment info from omx
                    OMX_U8 *ptr = static_cast<OMX_U8 *>(mem->pointer());
                    OMX_U32 pBuffer = ((reinterpret_cast<OMX_U32>(ptr)+(MTK_BUF_ADDR_ALIGNMENT_VALUE-1))&~(MTK_BUF_ADDR_ALIGNMENT_VALUE-1));
                    info.mData = new ABuffer((void*)pBuffer, def.nBufferSize);
                    ALOGD("Input[%d], %p(%p)", i, info.mData->data(), ptr);
#else
                    info.mData = new ABuffer(mem->pointer(), def.nBufferSize);
#endif
                }

                mBuffers[portIndex].push(info);
            }
        }
    }

    if (err != OK) {
        return err;
    }

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", ACodec::kWhatBuffersAllocated);

    notify->setInt32("portIndex", portIndex);

    sp<PortDescription> desc = new PortDescription;

    for (size_t i = 0; i < mBuffers[portIndex].size(); ++i) {
        const BufferInfo &info = mBuffers[portIndex][i];

        desc->addBuffer(info.mBufferID, info.mData);
    }

    notify->setObject("portDesc", desc);
    notify->post();

    return OK;
}

status_t ACodec::allocateOutputBuffersFromNativeWindow() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);
    def.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

#ifndef ANDROID_DEFAULT_CODE
    if (!strncmp("OMX.MTK.", mComponentName.c_str(), 8)) {
        uint32_t eHalColorFormat;
        switch (def.format.video.eColorFormat) {
            case OMX_COLOR_FormatYUV420Planar:
                //eHalColorFormat = HAL_PIXEL_FORMAT_YV12;
                eHalColorFormat = HAL_PIXEL_FORMAT_I420;
                break;
            case OMX_MTK_COLOR_FormatYV12:
                eHalColorFormat = HAL_PIXEL_FORMAT_YV12;
                break;
            case OMX_COLOR_FormatVendorMTKYUV:
                eHalColorFormat = HAL_PIXEL_FORMAT_NV12_BLK;
                break;
            default:
                //eHalColorFormat = HAL_PIXEL_FORMAT_YV12;
                eHalColorFormat = HAL_PIXEL_FORMAT_I420;
                break;           
        }

        ALOGD ("native_window_set_buffers_geometry W(%d), H(%d), Stride(%d), SliceH(%d)", def.format.video.nFrameWidth, def.format.video.nFrameHeight, def.format.video.nStride, def.format.video.nSliceHeight);

        err = native_window_set_buffers_geometry(
                mNativeWindow.get(),
                def.format.video.nStride,
                def.format.video.nSliceHeight,
                eHalColorFormat);
    } else {
#endif

        ALOGD ("native_window_set_buffers_geometry W(%d), H(%d), %d", def.format.video.nFrameWidth, def.format.video.nFrameHeight, (int)def.format.video.eColorFormat);
    err = native_window_set_buffers_geometry(
            mNativeWindow.get(),
            def.format.video.nFrameWidth,
            def.format.video.nFrameHeight,
            def.format.video.eColorFormat);
#ifndef ANDROID_DEFAULT_CODE
    }
#endif

    if (err != 0) {
        ALOGE("native_window_set_buffers_geometry failed: %s (%d)",
                strerror(-err), -err);
        return err;
    }

    // Set up the native window.
    OMX_U32 usage = 0;
    err = mOMX->getGraphicBufferUsage(mNode, kPortIndexOutput, &usage);
    if (err != 0) {
        ALOGW("querying usage flags from OMX IL component failed: %d", err);
        // XXX: Currently this error is logged, but not fatal.
        usage = 0;
    }

    if (mFlags & kFlagIsSecure) {
        usage |= GRALLOC_USAGE_PROTECTED;
    }

#ifndef ANDROID_DEFAULT_CODE
    usage |= (GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_SW_READ_OFTEN);
#endif

    // Make sure to check whether either Stagefright or the video decoder
    // requested protected buffers.
    if (usage & GRALLOC_USAGE_PROTECTED) {
        // Verify that the ANativeWindow sends images directly to
        // SurfaceFlinger.
        int queuesToNativeWindow = 0;
        err = mNativeWindow->query(
                mNativeWindow.get(), NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER,
                &queuesToNativeWindow);
        if (err != 0) {
            ALOGE("error authenticating native window: %d", err);
            return err;
        }
        if (queuesToNativeWindow != 1) {
            ALOGE("native window could not be authenticated");
            return PERMISSION_DENIED;
        }
    }

    err = native_window_set_usage(
            mNativeWindow.get(),
            usage | GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_EXTERNAL_DISP);

    if (err != 0) {
        ALOGE("native_window_set_usage failed: %s (%d)", strerror(-err), -err);
        return err;
    }

    int minUndequeuedBufs = 0;
    err = mNativeWindow->query(
            mNativeWindow.get(), NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS,
            &minUndequeuedBufs);

    if (err != 0) {
        ALOGE("NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS query failed: %s (%d)",
                strerror(-err), -err);
        return err;
    }

    // XXX: Is this the right logic to use?  It's not clear to me what the OMX
    // buffer counts refer to - how do they account for the renderer holding on
    // to buffers?
    if (def.nBufferCountActual < def.nBufferCountMin + minUndequeuedBufs) {
        OMX_U32 newBufferCount = def.nBufferCountMin + minUndequeuedBufs;
        def.nBufferCountActual = newBufferCount;
        err = mOMX->setParameter(
                mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

        if (err != OK) {
            ALOGE("[%s] setting nBufferCountActual to %lu failed: %d",
                    mComponentName.c_str(), newBufferCount, err);
            return err;
        }
    }

    err = native_window_set_buffer_count(
            mNativeWindow.get(), def.nBufferCountActual);

    if (err != 0) {
        ALOGE("native_window_set_buffer_count failed: %s (%d)", strerror(-err),
                -err);
        return err;
    }

    ALOGV("[%s] Allocating %lu buffers from a native window of size %lu on "
         "output port",
         mComponentName.c_str(), def.nBufferCountActual, def.nBufferSize);

    // Dequeue buffers and send them to OMX
    for (OMX_U32 i = 0; i < def.nBufferCountActual; i++) {
        ANativeWindowBuffer *buf;
        err = native_window_dequeue_buffer_and_wait(mNativeWindow.get(), &buf);
        if (err != 0) {
            ALOGE("dequeueBuffer failed: %s (%d)", strerror(-err), -err);
            break;
        }

        sp<GraphicBuffer> graphicBuffer(new GraphicBuffer(buf, false));
        BufferInfo info;
        info.mStatus = BufferInfo::OWNED_BY_US;
        info.mData = new ABuffer(0);
        info.mGraphicBuffer = graphicBuffer;
        mBuffers[kPortIndexOutput].push(info);

        IOMX::buffer_id bufferId;
        err = mOMX->useGraphicBuffer(mNode, kPortIndexOutput, graphicBuffer,
                &bufferId);
        if (err != 0) {
            ALOGE("registering GraphicBuffer %lu with OMX IL component failed: "
                 "%d", i, err);
            break;
        }

        mBuffers[kPortIndexOutput].editItemAt(i).mBufferID = bufferId;

        ALOGV("[%s] Registered graphic buffer with ID %p (pointer = %p)",
             mComponentName.c_str(),
             bufferId, graphicBuffer.get());
    }

    OMX_U32 cancelStart;
    OMX_U32 cancelEnd;

    if (err != 0) {
        // If an error occurred while dequeuing we need to cancel any buffers
        // that were dequeued.
        cancelStart = 0;
        cancelEnd = mBuffers[kPortIndexOutput].size();
    } else {
        // Return the last two buffers to the native window.
        cancelStart = def.nBufferCountActual - minUndequeuedBufs;
        cancelEnd = def.nBufferCountActual;
    }

    for (OMX_U32 i = cancelStart; i < cancelEnd; i++) {
        BufferInfo *info = &mBuffers[kPortIndexOutput].editItemAt(i);
        cancelBufferToNativeWindow(info);
    }

    return err;
}

status_t ACodec::cancelBufferToNativeWindow(BufferInfo *info) {
    CHECK_EQ((int)info->mStatus, (int)BufferInfo::OWNED_BY_US);

    ALOGV("[%s] Calling cancelBuffer on buffer %p",
         mComponentName.c_str(), info->mBufferID);

    int err = mNativeWindow->cancelBuffer(
        mNativeWindow.get(), info->mGraphicBuffer.get(), -1);

#ifndef ANDROID_DEFAULT_CODE
    if (err != 0) {
        LOGE("failed to cancel buffer from native window: %p, err = %d", mNativeWindow.get(), err);
        info->mStatus = BufferInfo::OWNED_BY_UNEXPECTED;
    } else {
#endif
    CHECK_EQ(err, 0);

    info->mStatus = BufferInfo::OWNED_BY_NATIVE_WINDOW;

#ifndef ANDROID_DEFAULT_CODE
    }
#endif
    return OK;
}

ACodec::BufferInfo *ACodec::dequeueBufferFromNativeWindow() {
    ANativeWindowBuffer *buf;
    int fenceFd = -1;
    if (native_window_dequeue_buffer_and_wait(mNativeWindow.get(), &buf) != 0) {
        ALOGE("dequeueBuffer failed.");
        return NULL;
    }

    for (size_t i = mBuffers[kPortIndexOutput].size(); i-- > 0;) {
        BufferInfo *info =
            &mBuffers[kPortIndexOutput].editItemAt(i);

        if (info->mGraphicBuffer->handle == buf->handle) {
            CHECK_EQ((int)info->mStatus,
                     (int)BufferInfo::OWNED_BY_NATIVE_WINDOW);

            info->mStatus = BufferInfo::OWNED_BY_US;

            return info;
        }
    }

#ifndef ANDROID_DEFAULT_CODE
    ALOGI("dequeue buffer from native window (%p), but not matched in %d output buffers",
           mNativeWindow.get(), mBuffers[kPortIndexOutput].size(), mNativeWindow.get());
    int err = mNativeWindow->cancelBuffer(mNativeWindow.get(), buf, -1); 
    ALOGI("\t\tcancel this unexpected buffer from native window, err = %d", err);
#else
    TRESPASS();
#endif

    return NULL;
}

status_t ACodec::freeBuffersOnPort(OMX_U32 portIndex) {
    for (size_t i = mBuffers[portIndex].size(); i-- > 0;) {
        CHECK_EQ((status_t)OK, freeBuffer(portIndex, i));
    }

    mDealer[portIndex].clear();

    return OK;
}

status_t ACodec::freeOutputBuffersNotOwnedByComponent() {
    for (size_t i = mBuffers[kPortIndexOutput].size(); i-- > 0;) {
        BufferInfo *info =
            &mBuffers[kPortIndexOutput].editItemAt(i);

        if (info->mStatus !=
                BufferInfo::OWNED_BY_COMPONENT) {
            // We shouldn't have sent out any buffers to the client at this
            // point.
            CHECK_NE((int)info->mStatus, (int)BufferInfo::OWNED_BY_DOWNSTREAM);

            CHECK_EQ((status_t)OK, freeBuffer(kPortIndexOutput, i));
        }
    }

    return OK;
}

status_t ACodec::freeBuffer(OMX_U32 portIndex, size_t i) {
    BufferInfo *info = &mBuffers[portIndex].editItemAt(i);

    CHECK(info->mStatus == BufferInfo::OWNED_BY_US
            || info->mStatus == BufferInfo::OWNED_BY_NATIVE_WINDOW);

    if (portIndex == kPortIndexOutput && mNativeWindow != NULL
            && info->mStatus == BufferInfo::OWNED_BY_US) {
        CHECK_EQ((status_t)OK, cancelBufferToNativeWindow(info));
    }

    CHECK_EQ(mOMX->freeBuffer(
                mNode, portIndex, info->mBufferID),
             (status_t)OK);

    mBuffers[portIndex].removeAt(i);

    return OK;
}

ACodec::BufferInfo *ACodec::findBufferByID(
        uint32_t portIndex, IOMX::buffer_id bufferID,
        ssize_t *index) {
    for (size_t i = 0; i < mBuffers[portIndex].size(); ++i) {
        BufferInfo *info = &mBuffers[portIndex].editItemAt(i);

        if (info->mBufferID == bufferID) {
            if (index != NULL) {
                *index = i;
            }
            return info;
        }
    }

    TRESPASS();

    return NULL;
}

status_t ACodec::setComponentRole(
        bool isEncoder, const char *mime) {
    struct MimeToRole {
        const char *mime;
        const char *decoderRole;
        const char *encoderRole;
    };

    static const MimeToRole kMimeToRole[] = {
        { MEDIA_MIMETYPE_AUDIO_MPEG,
            "audio_decoder.mp3", "audio_encoder.mp3" },
        { MEDIA_MIMETYPE_AUDIO_MPEG_LAYER_I,
            "audio_decoder.mp1", "audio_encoder.mp1" },
        { MEDIA_MIMETYPE_AUDIO_MPEG_LAYER_II,
            "audio_decoder.mp2", "audio_encoder.mp2" },
        { MEDIA_MIMETYPE_AUDIO_AMR_NB,
            "audio_decoder.amrnb", "audio_encoder.amrnb" },
        { MEDIA_MIMETYPE_AUDIO_AMR_WB,
            "audio_decoder.amrwb", "audio_encoder.amrwb" },
        { MEDIA_MIMETYPE_AUDIO_AAC,
            "audio_decoder.aac", "audio_encoder.aac" },
        { MEDIA_MIMETYPE_AUDIO_VORBIS,
            "audio_decoder.vorbis", "audio_encoder.vorbis" },
        { MEDIA_MIMETYPE_AUDIO_G711_MLAW,
            "audio_decoder.g711mlaw", "audio_encoder.g711mlaw" },
        { MEDIA_MIMETYPE_AUDIO_G711_ALAW,
            "audio_decoder.g711alaw", "audio_encoder.g711alaw" },
        { MEDIA_MIMETYPE_VIDEO_AVC,
            "video_decoder.avc", "video_encoder.avc" },
        { MEDIA_MIMETYPE_VIDEO_MPEG4,
            "video_decoder.mpeg4", "video_encoder.mpeg4" },
        { MEDIA_MIMETYPE_VIDEO_H263,
            "video_decoder.h263", "video_encoder.h263" },
        { MEDIA_MIMETYPE_VIDEO_VPX,
            "video_decoder.vpx", "video_encoder.vpx" },
        { MEDIA_MIMETYPE_AUDIO_RAW,
            "audio_decoder.raw", "audio_encoder.raw" },
        { MEDIA_MIMETYPE_AUDIO_FLAC,
            "audio_decoder.flac", "audio_encoder.flac" },
    };

    static const size_t kNumMimeToRole =
        sizeof(kMimeToRole) / sizeof(kMimeToRole[0]);

    size_t i;
    for (i = 0; i < kNumMimeToRole; ++i) {
        if (!strcasecmp(mime, kMimeToRole[i].mime)) {
            break;
        }
    }

    if (i == kNumMimeToRole) {
        return ERROR_UNSUPPORTED;
    }

    const char *role =
        isEncoder ? kMimeToRole[i].encoderRole
                  : kMimeToRole[i].decoderRole;

    if (role != NULL) {
        OMX_PARAM_COMPONENTROLETYPE roleParams;
        InitOMXParams(&roleParams);

        strncpy((char *)roleParams.cRole,
                role, OMX_MAX_STRINGNAME_SIZE - 1);

        roleParams.cRole[OMX_MAX_STRINGNAME_SIZE - 1] = '\0';

        status_t err = mOMX->setParameter(
                mNode, OMX_IndexParamStandardComponentRole,
                &roleParams, sizeof(roleParams));

        if (err != OK) {
            ALOGW("[%s] Failed to set standard component role '%s'.",
                 mComponentName.c_str(), role);

            return err;
        }
    }

    return OK;
}

status_t ACodec::configureCodec(
        const char *mime, const sp<AMessage> &msg) {
    int32_t encoder;
    if (!msg->findInt32("encoder", &encoder)) {
        encoder = false;
    }

    mIsEncoder = encoder;

    status_t err = setComponentRole(encoder /* isEncoder */, mime);

    if (err != OK) {
        return err;
    }

    int32_t bitRate = 0;
    // FLAC encoder doesn't need a bitrate, other encoders do
    if (encoder && strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_FLAC)
            && !msg->findInt32("bitrate", &bitRate)) {
        return INVALID_OPERATION;
    }

    int32_t storeMeta;
    if (encoder
            && msg->findInt32("store-metadata-in-buffers", &storeMeta)
            && storeMeta != 0) {
        err = mOMX->storeMetaDataInBuffers(mNode, kPortIndexInput, OMX_TRUE);

        if (err != OK) {
            ALOGE("[%s] storeMetaDataInBuffers failed w/ err %d",
                  mComponentName.c_str(), err);

            return err;
        }
    }

    int32_t prependSPSPPS;
    if (encoder
            && msg->findInt32("prepend-sps-pps-to-idr-frames", &prependSPSPPS)
            && prependSPSPPS != 0) {
        OMX_INDEXTYPE index;
        err = mOMX->getExtensionIndex(
                mNode,
                "OMX.google.android.index.prependSPSPPSToIDRFrames",
                &index);

        if (err == OK) {
            PrependSPSPPSToIDRFramesParams params;
            InitOMXParams(&params);
            params.bEnable = OMX_TRUE;

            err = mOMX->setParameter(
                    mNode, index, &params, sizeof(params));
        }

        if (err != OK) {
            ALOGE("Encoder could not be configured to emit SPS/PPS before "
                  "IDR frames. (err %d)", err);

            return err;
        }
    }

    if (!strncasecmp(mime, "video/", 6)) {
        if (encoder) {
            err = setupVideoEncoder(mime, msg);
        } else {
            int32_t width, height;
            if (!msg->findInt32("width", &width)
                    || !msg->findInt32("height", &height)) {
                err = INVALID_OPERATION;
            } else {
                err = setupVideoDecoder(mime, width, height);
            }
        }
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        int32_t numChannels, sampleRate;
        if (!msg->findInt32("channel-count", &numChannels)
                || !msg->findInt32("sample-rate", &sampleRate)) {
            err = INVALID_OPERATION;
        } else {
            int32_t isADTS, aacProfile;
            if (!msg->findInt32("is-adts", &isADTS)) {
                isADTS = 0;
            }
            if (!msg->findInt32("aac-profile", &aacProfile)) {
                aacProfile = OMX_AUDIO_AACObjectNull;
            }

			if (!msg->findInt32("bitrate", &bitRate)) {
                bitRate = 0;
				ALOGE("cannot find aac bit rate");
            }
            err = setupAACCodec(
                    encoder, numChannels, sampleRate, bitRate, aacProfile, isADTS != 0);
        }
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_NB)) {
        err = setupAMRCodec(encoder, false /* isWAMR */, bitRate);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_WB)) {
        err = setupAMRCodec(encoder, true /* isWAMR */, bitRate);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_G711_ALAW)
            || !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_G711_MLAW)) {
        // These are PCM-like formats with a fixed sample rate but
        // a variable number of channels.

        int32_t numChannels;
        if (!msg->findInt32("channel-count", &numChannels)) {
            err = INVALID_OPERATION;
        } else {
            err = setupG711Codec(encoder, numChannels);
        }
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_FLAC)) {
        int32_t numChannels, sampleRate, compressionLevel = -1;
        if (encoder &&
                (!msg->findInt32("channel-count", &numChannels)
                        || !msg->findInt32("sample-rate", &sampleRate))) {
            ALOGE("missing channel count or sample rate for FLAC encoder");
            err = INVALID_OPERATION;
        } else {
            if (encoder) {
                if (!msg->findInt32("flac-compression-level", &compressionLevel)) {
                    compressionLevel = 5;// default FLAC compression level
                } else if (compressionLevel < 0) {
                    ALOGW("compression level %d outside [0..8] range, using 0", compressionLevel);
                    compressionLevel = 0;
                } else if (compressionLevel > 8) {
                    ALOGW("compression level %d outside [0..8] range, using 8", compressionLevel);
                    compressionLevel = 8;
                }
            }
#ifndef ANDROID_DEFAULT_CODE            
            else
            {
                sp<ABuffer> buffer;
                if(msg->findBuffer("flacinfo", &buffer))
                {
                    ALOGW("acodec buffer size, %d", buffer->size()); ///buffer->data();
                    uint32_t type;        
                    typedef struct {
                        unsigned min_blocksize, max_blocksize;
                        unsigned min_framesize, max_framesize;
                        unsigned sample_rate;
                        unsigned channels;
                        unsigned bits_per_sample;
                        uint64_t total_samples;
                        unsigned char md5sum[16];
                        unsigned int mMaxBufferSize;
                        bool      has_stream_info;
                    } FLAC__StreamMetadata_Info_;
                    FLAC__StreamMetadata_Info_ data;
                    memcpy(&data, buffer->data(), buffer->size());

                    OMX_AUDIO_PARAM_FLACTYPE profile;
                    InitOMXParams(&profile);
                    profile.nPortIndex = OMX_DirInput;

                    status_t err = mOMX->getParameter(
                                            mNode, OMX_IndexParamAudioFlac, &profile, sizeof(profile));
                    CHECK_EQ((status_t)OK, err);

                    profile.channel_assignment =  OMX_AUDIO_FLAC__CHANNEL_ASSIGNMENT_LEFT_SIDE;
                    profile.total_samples = data.total_samples;     
                    profile.min_framesize = data.min_framesize;    
                    profile.max_framesize = data.max_framesize;        
                    profile.nSampleRate = data.sample_rate;           
                    profile.min_blocksize = data.min_blocksize;
                    profile.max_blocksize = data.max_blocksize;        
                    profile.nChannels = data.channels;    
                    profile.bits_per_sample = data.bits_per_sample;   
                    memcpy(profile.md5sum, data.md5sum, 16*sizeof(OMX_U8)); 

                    if(data.has_stream_info == true)
                        profile.has_stream_info = OMX_TRUE;
                    else
                        profile.has_stream_info = OMX_FALSE;
                   

                    ALOGD("kKeyFlacMetaInfo = %lld, %d, %d, %d, %d, %d, %d, %d",profile.total_samples, profile.min_framesize, profile.max_framesize, 
                    profile.nSampleRate, profile.min_blocksize, profile.max_blocksize, profile.nChannels, profile.bits_per_sample);
                    err = mOMX->setParameter(
                                    mNode, OMX_IndexParamAudioFlac, &profile, sizeof(profile));
                                    OMX_PARAM_PORTDEFINITIONTYPE def;
                                    InitOMXParams(&def);
                    def.nPortIndex = OMX_DirInput;

                    err = mOMX->getParameter(
                    mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
                    CHECK_EQ((status_t)OK, err);

                    def.nBufferSize =profile.max_framesize+16;;
                    err = mOMX->setParameter(
                    mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

                    OMX_PARAM_PORTDEFINITIONTYPE outputdef;
                    InitOMXParams(&outputdef);
                    outputdef.nPortIndex = OMX_DirOutput;

                    err = mOMX->getParameter(
                    mNode, OMX_IndexParamPortDefinition, &outputdef, sizeof(outputdef));
                    CHECK_EQ((status_t)OK, err);

                    if(profile.bits_per_sample/8 < 2)       //default output 16 bit pcm.
                        outputdef.nBufferSize = profile.max_blocksize * profile.nChannels * 2;
                    else
                        outputdef.nBufferSize = profile.max_blocksize * profile.nChannels * profile.bits_per_sample/8;
                    err = mOMX->setParameter(
                                    mNode, OMX_IndexParamPortDefinition, &outputdef, sizeof(outputdef));
                }
                
            }
#endif         
            err = setupFlacCodec(encoder, numChannels, sampleRate, compressionLevel);
        }
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_RAW)) {
        int32_t numChannels, sampleRate;
        if (encoder
                || !msg->findInt32("channel-count", &numChannels)
                || !msg->findInt32("sample-rate", &sampleRate)) {
            err = INVALID_OPERATION;
        } else {
            err = setupRawAudioFormat(kPortIndexInput, sampleRate, numChannels);
        }
    }

    if (!msg->findInt32("encoder-delay", &mEncoderDelay)) {
        mEncoderDelay = 0;
    }

    if (!msg->findInt32("encoder-padding", &mEncoderPadding)) {
        mEncoderPadding = 0;
    }

    if (msg->findInt32("channel-mask", &mChannelMask)) {
        mChannelMaskPresent = true;
    } else {
        mChannelMaskPresent = false;
    }

    int32_t maxInputSize;
    if (msg->findInt32("max-input-size", &maxInputSize)) {
        err = setMinBufferSize(kPortIndexInput, (size_t)maxInputSize);
    } else if (!strcmp("OMX.Nvidia.aac.decoder", mComponentName.c_str())) {
        err = setMinBufferSize(kPortIndexInput, 8192);  // XXX
    }

#ifndef ANDROID_DEFAULT_CODE
    if ((!strncmp("OMX.MTK.", mComponentName.c_str(), 8)) && (!mIsEncoder)) {
        OMX_BOOL value;
        // check if codec supports partial frames input
        status_t err = mOMX->getParameter(mNode, 
                (OMX_INDEXTYPE)OMX_IndexVendorMtkOmxPartialFrameQuerySupported, 
                &value, sizeof(value));
        mSupportsPartialFrames = value;
        if (err != OK) {
            mSupportsPartialFrames = false;
        }
        ALOGI("mSupportsPartialFrames %d err %d ", mSupportsPartialFrames, err);
    }
#ifdef DUMP_BITSTREAM
    struct timeval tv;
    struct tm *tm;
    gettimeofday(&tv, NULL);
    tm = localtime(&tv.tv_sec); 
    AString sName = StringPrintf("/sdcard/ACodec.%s.%02d%02d", mComponentName.c_str(), tm->tm_hour, tm->tm_min);
    mDumpFile = fopen(sName.c_str(), "wb");
    if (mDumpFile == NULL) {
        ALOGE("dump file cannot create %s", sName.c_str());
    }
#endif
    // mtk80902: porting rtsp settings from OMXCodec
    int32_t mode;
    if (msg->findInt32("rtsp-seek-mode", &mode) && mode != 0) {
	status_t err2 = OK;
	OMX_INDEXTYPE index = OMX_IndexMax;
	status_t err = mOMX->getExtensionIndex(mNode, "OMX.MTK.index.param.video.StreamingMode", &index);
	if (err == OK) {
	    OMX_BOOL m = OMX_TRUE; 
	    err2 = mOMX->setParameter(mNode, index, &m, sizeof(m));
	}
	ALOGI("set StreamingMode, index = %x, err = %x, err2 = %x", index, err, err2);
    }
    int32_t number = -1;
    if (msg->findInt32("max-queue-buffer", &number) && number > 0) {
	mMaxQueueBufferNum = number;
    }
    if (msg->findInt32("input-buffer-number", &number) && number > 0) {
	OMX_PARAM_PORTDEFINITIONTYPE def;
	InitOMXParams(&def);
	def.nPortIndex = kPortIndexInput;

	status_t err = mOMX->getParameter(
		    mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
	CHECK_EQ((int)err, (int)OK);

	def.nBufferCountActual = number > (int32_t)def.nBufferCountMin 
		    ? number : def.nBufferCountMin;

	err = mOMX->setParameter(
		    mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
	CHECK_EQ((int)err, (int)OK);

	err = mOMX->getParameter(
		    mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));
	CHECK_EQ((int)err, (int)OK);
    }
// mtk80902: porting from OMXCodec - is video enc/dec
    if (false == mIsEncoder) {
        if ((!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) ||
            (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) ||
            (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) ||
            (!strcasecmp(MEDIA_MIMETYPE_VIDEO_WMV, mime)) ||        // Morris Yang add for ASF
            (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG2, mime)) ||
            (!strcasecmp(MEDIA_MIMETYPE_VIDEO_VPX, mime))) {           
            mIsVideoDecoder = true;
/*
            char value[PROPERTY_VALUE_MAX];
            property_get("omxcodec.video.input.error.rate", value, "0.0");	
            mVideoInputErrorRate = atof(value);
            if (mVideoInputErrorRate > 0) {
                mPropFlags |= OMXCODEC_ENABLE_VIDEO_INPUT_ERROR_PATTERNS;
            }
            ALOGD ("mVideoInputErrorRate(%f)", mVideoInputErrorRate);*/
        }
    }
    else {
        if ((!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) ||
            (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) ||
            (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime))) {
            mIsVideoEncoder = true;
/*
            mCameraMeta = new MetaData;

            if (!mOMXLivesLocally) {
                mQuirks &= ~kAvoidMemcopyInputRecordingFrames;
            }*/
        }
    }
/*
    ALOGD ("!@@!>> create tid (%d) OMXCodec mOMXLivesLocally=%d, mIsVideoDecoder(%d), mIsVideoEncoder(%d), mime(%s)", 
		gettid(), mOMXLivesLocally, mIsVideoDecoder, mIsVideoEncoder, mime);*/
#endif

    return err;
}

status_t ACodec::setMinBufferSize(OMX_U32 portIndex, size_t size) {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);
    def.nPortIndex = portIndex;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    if (def.nBufferSize >= size) {
        return OK;
    }

    def.nBufferSize = size;

    err = mOMX->setParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    err = mOMX->getParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    CHECK(def.nBufferSize >= size);

    return OK;
}

status_t ACodec::selectAudioPortFormat(
        OMX_U32 portIndex, OMX_AUDIO_CODINGTYPE desiredFormat) {
    OMX_AUDIO_PARAM_PORTFORMATTYPE format;
    InitOMXParams(&format);

    format.nPortIndex = portIndex;
    for (OMX_U32 index = 0;; ++index) {
        format.nIndex = index;

        status_t err = mOMX->getParameter(
                mNode, OMX_IndexParamAudioPortFormat,
                &format, sizeof(format));

        if (err != OK) {
            return err;
        }

        if (format.eEncoding == desiredFormat) {
            break;
        }
    }

    return mOMX->setParameter(
            mNode, OMX_IndexParamAudioPortFormat, &format, sizeof(format));
}

status_t ACodec::setupAACCodec(
        bool encoder, int32_t numChannels, int32_t sampleRate,
        int32_t bitRate, int32_t aacProfile, bool isADTS) {
    if (encoder && isADTS) {
        return -EINVAL;
    }

    status_t err = setupRawAudioFormat(
            encoder ? kPortIndexInput : kPortIndexOutput,
            sampleRate,
            numChannels);

    if (err != OK) {
        return err;
    }

    if (encoder) {
        err = selectAudioPortFormat(kPortIndexOutput, OMX_AUDIO_CodingAAC);

        if (err != OK) {
            return err;
        }

        OMX_PARAM_PORTDEFINITIONTYPE def;
        InitOMXParams(&def);
        def.nPortIndex = kPortIndexOutput;

        err = mOMX->getParameter(
                mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

        if (err != OK) {
            return err;
        }

        def.format.audio.bFlagErrorConcealment = OMX_TRUE;
        def.format.audio.eEncoding = OMX_AUDIO_CodingAAC;

        err = mOMX->setParameter(
                mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

        if (err != OK) {
            return err;
        }

        OMX_AUDIO_PARAM_AACPROFILETYPE profile;
        InitOMXParams(&profile);
        profile.nPortIndex = kPortIndexOutput;

        err = mOMX->getParameter(
                mNode, OMX_IndexParamAudioAac, &profile, sizeof(profile));

        if (err != OK) {
            return err;
        }

        profile.nChannels = numChannels;

        profile.eChannelMode =
            (numChannels == 1)
                ? OMX_AUDIO_ChannelModeMono: OMX_AUDIO_ChannelModeStereo;

        profile.nSampleRate = sampleRate;
        profile.nBitRate = bitRate;
        profile.nAudioBandWidth = 0;
        profile.nFrameLength = 0;
        profile.nAACtools = OMX_AUDIO_AACToolAll;
        profile.nAACERtools = OMX_AUDIO_AACERNone;
        profile.eAACProfile = (OMX_AUDIO_AACPROFILETYPE) aacProfile;
        profile.eAACStreamFormat = OMX_AUDIO_AACStreamFormatMP4FF;

        err = mOMX->setParameter(
                mNode, OMX_IndexParamAudioAac, &profile, sizeof(profile));

        if (err != OK) {
            return err;
        }

        return err;
    }

    OMX_AUDIO_PARAM_AACPROFILETYPE profile;
    InitOMXParams(&profile);
    profile.nPortIndex = kPortIndexInput;

    err = mOMX->getParameter(
            mNode, OMX_IndexParamAudioAac, &profile, sizeof(profile));

    if (err != OK) {
        return err;
    }

    profile.nChannels = numChannels;
    profile.nSampleRate = sampleRate;

    profile.eAACStreamFormat =
        isADTS
            ? OMX_AUDIO_AACStreamFormatMP4ADTS
            : OMX_AUDIO_AACStreamFormatMP4FF;

    return mOMX->setParameter(
            mNode, OMX_IndexParamAudioAac, &profile, sizeof(profile));
}

static OMX_AUDIO_AMRBANDMODETYPE pickModeFromBitRate(
        bool isAMRWB, int32_t bps) {
    if (isAMRWB) {
        if (bps <= 6600) {
            return OMX_AUDIO_AMRBandModeWB0;
        } else if (bps <= 8850) {
            return OMX_AUDIO_AMRBandModeWB1;
        } else if (bps <= 12650) {
            return OMX_AUDIO_AMRBandModeWB2;
        } else if (bps <= 14250) {
            return OMX_AUDIO_AMRBandModeWB3;
        } else if (bps <= 15850) {
            return OMX_AUDIO_AMRBandModeWB4;
        } else if (bps <= 18250) {
            return OMX_AUDIO_AMRBandModeWB5;
        } else if (bps <= 19850) {
            return OMX_AUDIO_AMRBandModeWB6;
        } else if (bps <= 23050) {
            return OMX_AUDIO_AMRBandModeWB7;
        }

        // 23850 bps
        return OMX_AUDIO_AMRBandModeWB8;
    } else {  // AMRNB
        if (bps <= 4750) {
            return OMX_AUDIO_AMRBandModeNB0;
        } else if (bps <= 5150) {
            return OMX_AUDIO_AMRBandModeNB1;
        } else if (bps <= 5900) {
            return OMX_AUDIO_AMRBandModeNB2;
        } else if (bps <= 6700) {
            return OMX_AUDIO_AMRBandModeNB3;
        } else if (bps <= 7400) {
            return OMX_AUDIO_AMRBandModeNB4;
        } else if (bps <= 7950) {
            return OMX_AUDIO_AMRBandModeNB5;
        } else if (bps <= 10200) {
            return OMX_AUDIO_AMRBandModeNB6;
        }

        // 12200 bps
        return OMX_AUDIO_AMRBandModeNB7;
    }
}

status_t ACodec::setupAMRCodec(bool encoder, bool isWAMR, int32_t bitrate) {
    OMX_AUDIO_PARAM_AMRTYPE def;
    InitOMXParams(&def);
    def.nPortIndex = encoder ? kPortIndexOutput : kPortIndexInput;

    status_t err =
        mOMX->getParameter(mNode, OMX_IndexParamAudioAmr, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    def.eAMRFrameFormat = OMX_AUDIO_AMRFrameFormatFSF;
    def.eAMRBandMode = pickModeFromBitRate(isWAMR, bitrate);

    err = mOMX->setParameter(
            mNode, OMX_IndexParamAudioAmr, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    return setupRawAudioFormat(
            encoder ? kPortIndexInput : kPortIndexOutput,
            isWAMR ? 16000 : 8000 /* sampleRate */,
            1 /* numChannels */);
}

status_t ACodec::setupG711Codec(bool encoder, int32_t numChannels) {
    CHECK(!encoder);  // XXX TODO

    return setupRawAudioFormat(
            kPortIndexInput, 8000 /* sampleRate */, numChannels);
}

status_t ACodec::setupFlacCodec(
        bool encoder, int32_t numChannels, int32_t sampleRate, int32_t compressionLevel) {

    if (encoder) {
        OMX_AUDIO_PARAM_FLACTYPE def;
        InitOMXParams(&def);
        def.nPortIndex = kPortIndexOutput;

        // configure compression level
        status_t err = mOMX->getParameter(mNode, OMX_IndexParamAudioFlac, &def, sizeof(def));
        if (err != OK) {
            ALOGE("setupFlacCodec(): Error %d getting OMX_IndexParamAudioFlac parameter", err);
            return err;
        }
        def.nCompressionLevel = compressionLevel;
        err = mOMX->setParameter(mNode, OMX_IndexParamAudioFlac, &def, sizeof(def));
        if (err != OK) {
            ALOGE("setupFlacCodec(): Error %d setting OMX_IndexParamAudioFlac parameter", err);
            return err;
        }
    }

    return setupRawAudioFormat(
            encoder ? kPortIndexInput : kPortIndexOutput,
            sampleRate,
            numChannels);
}

status_t ACodec::setupRawAudioFormat(
        OMX_U32 portIndex, int32_t sampleRate, int32_t numChannels) {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);
    def.nPortIndex = portIndex;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    def.format.audio.eEncoding = OMX_AUDIO_CodingPCM;

    err = mOMX->setParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    OMX_AUDIO_PARAM_PCMMODETYPE pcmParams;
    InitOMXParams(&pcmParams);
    pcmParams.nPortIndex = portIndex;

    err = mOMX->getParameter(
            mNode, OMX_IndexParamAudioPcm, &pcmParams, sizeof(pcmParams));

    if (err != OK) {
        return err;
    }

    pcmParams.nChannels = numChannels;
    pcmParams.eNumData = OMX_NumericalDataSigned;
    pcmParams.bInterleaved = OMX_TRUE;
    pcmParams.nBitPerSample = 16;
    pcmParams.nSamplingRate = sampleRate;
    pcmParams.ePCMMode = OMX_AUDIO_PCMModeLinear;

    if (getOMXChannelMapping(numChannels, pcmParams.eChannelMapping) != OK) {
        return OMX_ErrorNone;
    }

    return mOMX->setParameter(
            mNode, OMX_IndexParamAudioPcm, &pcmParams, sizeof(pcmParams));
}

status_t ACodec::setVideoPortFormatType(
        OMX_U32 portIndex,
        OMX_VIDEO_CODINGTYPE compressionFormat,
        OMX_COLOR_FORMATTYPE colorFormat) {
    OMX_VIDEO_PARAM_PORTFORMATTYPE format;
    InitOMXParams(&format);
    format.nPortIndex = portIndex;
    format.nIndex = 0;
    bool found = false;

    OMX_U32 index = 0;
    for (;;) {
        format.nIndex = index;
        status_t err = mOMX->getParameter(
                mNode, OMX_IndexParamVideoPortFormat,
                &format, sizeof(format));

        if (err != OK) {
            return err;
        }

        // The following assertion is violated by TI's video decoder.
        // CHECK_EQ(format.nIndex, index);

        if (!strcmp("OMX.TI.Video.encoder", mComponentName.c_str())) {
            if (portIndex == kPortIndexInput
                    && colorFormat == format.eColorFormat) {
                // eCompressionFormat does not seem right.
                found = true;
                break;
            }
            if (portIndex == kPortIndexOutput
                    && compressionFormat == format.eCompressionFormat) {
                // eColorFormat does not seem right.
                found = true;
                break;
            }
        }

        if (format.eCompressionFormat == compressionFormat
            && format.eColorFormat == colorFormat) {
            found = true;
            break;
        }

        ++index;
    }

    if (!found) {
        return UNKNOWN_ERROR;
    }

    status_t err = mOMX->setParameter(
            mNode, OMX_IndexParamVideoPortFormat,
            &format, sizeof(format));

    return err;
}

status_t ACodec::setSupportedOutputFormat() {
    OMX_VIDEO_PARAM_PORTFORMATTYPE format;
    InitOMXParams(&format);
    format.nPortIndex = kPortIndexOutput;
    format.nIndex = 0;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamVideoPortFormat,
            &format, sizeof(format));
    CHECK_EQ(err, (status_t)OK);
    CHECK_EQ((int)format.eCompressionFormat, (int)OMX_VIDEO_CodingUnused);

    CHECK(format.eColorFormat == OMX_COLOR_FormatYUV420Planar
           || format.eColorFormat == OMX_COLOR_FormatYUV420SemiPlanar
           || format.eColorFormat == OMX_COLOR_FormatCbYCrY
           || format.eColorFormat == OMX_TI_COLOR_FormatYUV420PackedSemiPlanar
//           || format.eColorFormat == OMX_QCOM_COLOR_FormatYUV420PackedSemiPlanar64x32Tile2m8ka
#ifndef ANDROID_DEFAULT_CODE
           || format.eColorFormat == OMX_MTK_COLOR_FormatYV12
           || format.eColorFormat == OMX_COLOR_FormatVendorMTKYUV
#endif
           || format.eColorFormat == OMX_QCOM_COLOR_FormatYVU420SemiPlanar);

    return mOMX->setParameter(
            mNode, OMX_IndexParamVideoPortFormat,
            &format, sizeof(format));
}

static status_t GetVideoCodingTypeFromMime(
        const char *mime, OMX_VIDEO_CODINGTYPE *codingType) {
    if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) {
        *codingType = OMX_VIDEO_CodingAVC;
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
        *codingType = OMX_VIDEO_CodingMPEG4;
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
        *codingType = OMX_VIDEO_CodingH263;
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG2, mime)) {
        *codingType = OMX_VIDEO_CodingMPEG2;
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_VPX, mime)) {
        *codingType = OMX_VIDEO_CodingVPX;
    } else {
        *codingType = OMX_VIDEO_CodingUnused;
        return ERROR_UNSUPPORTED;
    }

    return OK;
}

status_t ACodec::setupVideoDecoder(
        const char *mime, int32_t width, int32_t height) {
    OMX_VIDEO_CODINGTYPE compressionFormat;
    status_t err = GetVideoCodingTypeFromMime(mime, &compressionFormat);

    if (err != OK) {
        return err;
    }

    err = setVideoPortFormatType(
            kPortIndexInput, compressionFormat, OMX_COLOR_FormatUnused);

    if (err != OK) {
        return err;
    }

    err = setSupportedOutputFormat();

    if (err != OK) {
        return err;
    }

    err = setVideoFormatOnPort(
            kPortIndexInput, width, height, compressionFormat);

    if (err != OK) {
        return err;
    }

    err = setVideoFormatOnPort(
            kPortIndexOutput, width, height, OMX_VIDEO_CodingUnused);

    if (err != OK) {
        return err;
    }

    return OK;
}

status_t ACodec::setupVideoEncoder(const char *mime, const sp<AMessage> &msg) {
    int32_t tmp;
    if (!msg->findInt32("color-format", &tmp)) {
        return INVALID_OPERATION;
    }

    OMX_COLOR_FORMATTYPE colorFormat =
        static_cast<OMX_COLOR_FORMATTYPE>(tmp);

    status_t err = setVideoPortFormatType(
            kPortIndexInput, OMX_VIDEO_CodingUnused, colorFormat);

    if (err != OK) {
        ALOGE("[%s] does not support color format %d",
              mComponentName.c_str(), colorFormat);

        return err;
    }

    /* Input port configuration */

    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    OMX_VIDEO_PORTDEFINITIONTYPE *video_def = &def.format.video;

    def.nPortIndex = kPortIndexInput;

    err = mOMX->getParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    int32_t width, height, bitrate;
    if (!msg->findInt32("width", &width)
            || !msg->findInt32("height", &height)
            || !msg->findInt32("bitrate", &bitrate)) {
        return INVALID_OPERATION;
    }

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;

    int32_t stride;
    if (!msg->findInt32("stride", &stride)) {
        stride = width;
    }

    video_def->nStride = stride;

    int32_t sliceHeight;
    if (!msg->findInt32("slice-height", &sliceHeight)) {
        sliceHeight = height;
    }

    video_def->nSliceHeight = sliceHeight;

    def.nBufferSize = (video_def->nStride * video_def->nSliceHeight * 3) / 2;

#ifndef ANDROID_DEFAULT_CODE
     {
         int32_t  inputbufferCnt;
         if (msg->findInt32("inputbuffercnt", &inputbufferCnt)) {
            def.nBufferCountActual  = inputbufferCnt;
	     ALOGI("input buffer count is %d", inputbufferCnt);
         }
     }
#endif

    float frameRate;
    if (!msg->findFloat("frame-rate", &frameRate)) {
        int32_t tmp;
        if (!msg->findInt32("frame-rate", &tmp)) {
            return INVALID_OPERATION;
        }
        frameRate = (float)tmp;
    }

    video_def->xFramerate = (OMX_U32)(frameRate * 65536.0f);
    video_def->eCompressionFormat = OMX_VIDEO_CodingUnused;
    video_def->eColorFormat = colorFormat;

    err = mOMX->setParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        ALOGE("[%s] failed to set input port definition parameters.",
              mComponentName.c_str());

        return err;
    }

    /* Output port configuration */

    OMX_VIDEO_CODINGTYPE compressionFormat;
    err = GetVideoCodingTypeFromMime(mime, &compressionFormat);

    if (err != OK) {
        return err;
    }

    err = setVideoPortFormatType(
            kPortIndexOutput, compressionFormat, OMX_COLOR_FormatUnused);

    if (err != OK) {
        ALOGE("[%s] does not support compression format %d",
             mComponentName.c_str(), compressionFormat);

        return err;
    }

    def.nPortIndex = kPortIndexOutput;

    err = mOMX->getParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        return err;
    }

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;
    video_def->xFramerate = 0;
    video_def->nBitrate = bitrate;
    video_def->eCompressionFormat = compressionFormat;
    video_def->eColorFormat = OMX_COLOR_FormatUnused;

    err = mOMX->setParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    if (err != OK) {
        ALOGE("[%s] failed to set output port definition parameters.",
              mComponentName.c_str());

        return err;
    }

    switch (compressionFormat) {
        case OMX_VIDEO_CodingMPEG4:
            err = setupMPEG4EncoderParameters(msg);
            break;

        case OMX_VIDEO_CodingH263:
            err = setupH263EncoderParameters(msg);
            break;

        case OMX_VIDEO_CodingAVC:
            err = setupAVCEncoderParameters(msg);
            break;

        default:
            break;
    }

    ALOGI("setupVideoEncoder succeeded");

    return err;
}

static OMX_U32 setPFramesSpacing(int32_t iFramesInterval, int32_t frameRate) {
    if (iFramesInterval < 0) {
        return 0xFFFFFFFF;
    } else if (iFramesInterval == 0) {
        return 0;
    }
    OMX_U32 ret = frameRate * iFramesInterval;
    CHECK(ret > 1);
    return ret;
}

static OMX_VIDEO_CONTROLRATETYPE getBitrateMode(const sp<AMessage> &msg) {
    int32_t tmp;
    if (!msg->findInt32("bitrate-mode", &tmp)) {
        return OMX_Video_ControlRateVariable;
    }

    return static_cast<OMX_VIDEO_CONTROLRATETYPE>(tmp);
}

status_t ACodec::setupMPEG4EncoderParameters(const sp<AMessage> &msg) {
    int32_t bitrate, iFrameInterval;
    if (!msg->findInt32("bitrate", &bitrate)
            || !msg->findInt32("i-frame-interval", &iFrameInterval)) {
        return INVALID_OPERATION;
    }

    OMX_VIDEO_CONTROLRATETYPE bitrateMode = getBitrateMode(msg);

    float frameRate;
    if (!msg->findFloat("frame-rate", &frameRate)) {
        int32_t tmp;
        if (!msg->findInt32("frame-rate", &tmp)) {
            return INVALID_OPERATION;
        }
        frameRate = (float)tmp;
    }

    OMX_VIDEO_PARAM_MPEG4TYPE mpeg4type;
    InitOMXParams(&mpeg4type);
    mpeg4type.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamVideoMpeg4, &mpeg4type, sizeof(mpeg4type));

    if (err != OK) {
        return err;
    }

    mpeg4type.nSliceHeaderSpacing = 0;
    mpeg4type.bSVH = OMX_FALSE;
    mpeg4type.bGov = OMX_FALSE;

    mpeg4type.nAllowedPictureTypes =
        OMX_VIDEO_PictureTypeI | OMX_VIDEO_PictureTypeP;

    mpeg4type.nPFrames = setPFramesSpacing(iFrameInterval, frameRate);
    if (mpeg4type.nPFrames == 0) {
        mpeg4type.nAllowedPictureTypes = OMX_VIDEO_PictureTypeI;
    }
    mpeg4type.nBFrames = 0;
    mpeg4type.nIDCVLCThreshold = 0;
    mpeg4type.bACPred = OMX_TRUE;
    mpeg4type.nMaxPacketSize = 256;
    mpeg4type.nTimeIncRes = 1000;
    mpeg4type.nHeaderExtension = 0;
    mpeg4type.bReversibleVLC = OMX_FALSE;

    int32_t profile;
    if (msg->findInt32("profile", &profile)) {
        int32_t level;
        if (!msg->findInt32("level", &level)) {
            return INVALID_OPERATION;
        }

        err = verifySupportForProfileAndLevel(profile, level);

        if (err != OK) {
            return err;
        }

        mpeg4type.eProfile = static_cast<OMX_VIDEO_MPEG4PROFILETYPE>(profile);
        mpeg4type.eLevel = static_cast<OMX_VIDEO_MPEG4LEVELTYPE>(level);
    }

    err = mOMX->setParameter(
            mNode, OMX_IndexParamVideoMpeg4, &mpeg4type, sizeof(mpeg4type));

    if (err != OK) {
        return err;
    }

    err = configureBitrate(bitrate, bitrateMode);

    if (err != OK) {
        return err;
    }

    return setupErrorCorrectionParameters();
}

status_t ACodec::setupH263EncoderParameters(const sp<AMessage> &msg) {
    int32_t bitrate, iFrameInterval;
    if (!msg->findInt32("bitrate", &bitrate)
            || !msg->findInt32("i-frame-interval", &iFrameInterval)) {
        return INVALID_OPERATION;
    }

    OMX_VIDEO_CONTROLRATETYPE bitrateMode = getBitrateMode(msg);


    float frameRate;
    if (!msg->findFloat("frame-rate", &frameRate)) {
        int32_t tmp;
        if (!msg->findInt32("frame-rate", &tmp)) {
            return INVALID_OPERATION;
        }
        frameRate = (float)tmp;
    }

    OMX_VIDEO_PARAM_H263TYPE h263type;
    InitOMXParams(&h263type);
    h263type.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamVideoH263, &h263type, sizeof(h263type));

    if (err != OK) {
        return err;
    }

    h263type.nAllowedPictureTypes =
        OMX_VIDEO_PictureTypeI | OMX_VIDEO_PictureTypeP;

    h263type.nPFrames = setPFramesSpacing(iFrameInterval, frameRate);
    if (h263type.nPFrames == 0) {
        h263type.nAllowedPictureTypes = OMX_VIDEO_PictureTypeI;
    }
    h263type.nBFrames = 0;

    int32_t profile;
    if (msg->findInt32("profile", &profile)) {
        int32_t level;
        if (!msg->findInt32("level", &level)) {
            return INVALID_OPERATION;
        }

        err = verifySupportForProfileAndLevel(profile, level);

        if (err != OK) {
            return err;
        }

        h263type.eProfile = static_cast<OMX_VIDEO_H263PROFILETYPE>(profile);
        h263type.eLevel = static_cast<OMX_VIDEO_H263LEVELTYPE>(level);
    }

    h263type.bPLUSPTYPEAllowed = OMX_FALSE;
    h263type.bForceRoundingTypeToZero = OMX_FALSE;
    h263type.nPictureHeaderRepetition = 0;
    h263type.nGOBHeaderInterval = 0;

    err = mOMX->setParameter(
            mNode, OMX_IndexParamVideoH263, &h263type, sizeof(h263type));

    if (err != OK) {
        return err;
    }

    err = configureBitrate(bitrate, bitrateMode);

    if (err != OK) {
        return err;
    }

    return setupErrorCorrectionParameters();
}

status_t ACodec::setupAVCEncoderParameters(const sp<AMessage> &msg) {
    int32_t bitrate, iFrameInterval;
    if (!msg->findInt32("bitrate", &bitrate)
            || !msg->findInt32("i-frame-interval", &iFrameInterval)) {
        return INVALID_OPERATION;
    }

    OMX_VIDEO_CONTROLRATETYPE bitrateMode = getBitrateMode(msg);
    float frameRate;
    if (!msg->findFloat("frame-rate", &frameRate)) {
        int32_t tmp;
        if (!msg->findInt32("frame-rate", &tmp)) {
            return INVALID_OPERATION;
        }
        frameRate = (float)tmp;
    }

    OMX_VIDEO_PARAM_AVCTYPE h264type;
    InitOMXParams(&h264type);
    h264type.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamVideoAvc, &h264type, sizeof(h264type));

    if (err != OK) {
        return err;
    }

    h264type.nAllowedPictureTypes =
        OMX_VIDEO_PictureTypeI | OMX_VIDEO_PictureTypeP;

    int32_t profile;
    if (msg->findInt32("profile", &profile)) {
        int32_t level;
        if (!msg->findInt32("level", &level)) {
            return INVALID_OPERATION;
        }

        err = verifySupportForProfileAndLevel(profile, level);

        if (err != OK) {
            return err;
        }

        h264type.eProfile = static_cast<OMX_VIDEO_AVCPROFILETYPE>(profile);
        h264type.eLevel = static_cast<OMX_VIDEO_AVCLEVELTYPE>(level);
    }

    // XXX
    if (h264type.eProfile != OMX_VIDEO_AVCProfileBaseline) {
        ALOGW("Use baseline profile instead of %d for AVC recording",
            h264type.eProfile);
        h264type.eProfile = OMX_VIDEO_AVCProfileBaseline;
    }

    if (h264type.eProfile == OMX_VIDEO_AVCProfileBaseline) {
        h264type.nSliceHeaderSpacing = 0;
        h264type.bUseHadamard = OMX_TRUE;
        h264type.nRefFrames = 1;
        h264type.nBFrames = 0;
        h264type.nPFrames = setPFramesSpacing(iFrameInterval, frameRate);
        if (h264type.nPFrames == 0) {
            h264type.nAllowedPictureTypes = OMX_VIDEO_PictureTypeI;
        }
        h264type.nRefIdx10ActiveMinus1 = 0;
        h264type.nRefIdx11ActiveMinus1 = 0;
        h264type.bEntropyCodingCABAC = OMX_FALSE;
        h264type.bWeightedPPrediction = OMX_FALSE;
        h264type.bconstIpred = OMX_FALSE;
        h264type.bDirect8x8Inference = OMX_FALSE;
        h264type.bDirectSpatialTemporal = OMX_FALSE;
        h264type.nCabacInitIdc = 0;
    }

    if (h264type.nBFrames != 0) {
        h264type.nAllowedPictureTypes |= OMX_VIDEO_PictureTypeB;
    }

    h264type.bEnableUEP = OMX_FALSE;
    h264type.bEnableFMO = OMX_FALSE;
    h264type.bEnableASO = OMX_FALSE;
    h264type.bEnableRS = OMX_FALSE;
    h264type.bFrameMBsOnly = OMX_TRUE;
    h264type.bMBAFF = OMX_FALSE;
    h264type.eLoopFilterMode = OMX_VIDEO_AVCLoopFilterEnable;

    err = mOMX->setParameter(
            mNode, OMX_IndexParamVideoAvc, &h264type, sizeof(h264type));

    if (err != OK) {
        return err;
    }

#ifndef ANDROID_DEFAULT_CODE
    err = setVEncIInterval(iFrameInterval);
    if (err != OK) {
        return err;
    }
#endif//ANDROID_DEFAULT_CODE

    return configureBitrate(bitrate, bitrateMode);
}

status_t ACodec::verifySupportForProfileAndLevel(
        int32_t profile, int32_t level) {
    OMX_VIDEO_PARAM_PROFILELEVELTYPE params;
    InitOMXParams(&params);
    params.nPortIndex = kPortIndexOutput;

    for (params.nProfileIndex = 0;; ++params.nProfileIndex) {
        status_t err = mOMX->getParameter(
                mNode,
                OMX_IndexParamVideoProfileLevelQuerySupported,
                &params,
                sizeof(params));

        if (err != OK) {
            return err;
        }

        int32_t supportedProfile = static_cast<int32_t>(params.eProfile);
        int32_t supportedLevel = static_cast<int32_t>(params.eLevel);

        if (profile == supportedProfile && level <= supportedLevel) {
            return OK;
        }
    }
}

status_t ACodec::configureBitrate(
        int32_t bitrate, OMX_VIDEO_CONTROLRATETYPE bitrateMode) {
    OMX_VIDEO_PARAM_BITRATETYPE bitrateType;
    InitOMXParams(&bitrateType);
    bitrateType.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamVideoBitrate,
            &bitrateType, sizeof(bitrateType));

    if (err != OK) {
        return err;
    }

    bitrateType.eControlRate = bitrateMode;
    bitrateType.nTargetBitrate = bitrate;

    return mOMX->setParameter(
            mNode, OMX_IndexParamVideoBitrate,
            &bitrateType, sizeof(bitrateType));
}

status_t ACodec::setupErrorCorrectionParameters() {
    OMX_VIDEO_PARAM_ERRORCORRECTIONTYPE errorCorrectionType;
    InitOMXParams(&errorCorrectionType);
    errorCorrectionType.nPortIndex = kPortIndexOutput;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamVideoErrorCorrection,
            &errorCorrectionType, sizeof(errorCorrectionType));

    if (err != OK) {
        return OK;  // Optional feature. Ignore this failure
    }

    errorCorrectionType.bEnableHEC = OMX_FALSE;
    errorCorrectionType.bEnableResync = OMX_TRUE;
    errorCorrectionType.nResynchMarkerSpacing = 256;
    errorCorrectionType.bEnableDataPartitioning = OMX_FALSE;
    errorCorrectionType.bEnableRVLC = OMX_FALSE;

    return mOMX->setParameter(
            mNode, OMX_IndexParamVideoErrorCorrection,
            &errorCorrectionType, sizeof(errorCorrectionType));
}

status_t ACodec::setVideoFormatOnPort(
        OMX_U32 portIndex,
        int32_t width, int32_t height, OMX_VIDEO_CODINGTYPE compressionFormat) {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);
    def.nPortIndex = portIndex;

    OMX_VIDEO_PORTDEFINITIONTYPE *video_def = &def.format.video;

    status_t err = mOMX->getParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    CHECK_EQ(err, (status_t)OK);

    if (portIndex == kPortIndexInput) {
        // XXX Need a (much) better heuristic to compute input buffer sizes.
        const size_t X = 64 * 1024;
        if (def.nBufferSize < X) {
            def.nBufferSize = X;
        }
    }

    CHECK_EQ((int)def.eDomain, (int)OMX_PortDomainVideo);

    video_def->nFrameWidth = width;
    video_def->nFrameHeight = height;

    if (portIndex == kPortIndexInput) {
        video_def->eCompressionFormat = compressionFormat;
        video_def->eColorFormat = OMX_COLOR_FormatUnused;
    }

    err = mOMX->setParameter(
            mNode, OMX_IndexParamPortDefinition, &def, sizeof(def));

    return err;
}

status_t ACodec::initNativeWindow() {
    if (mNativeWindow != NULL) {
        return mOMX->enableGraphicBuffers(mNode, kPortIndexOutput, OMX_TRUE);
    }

    mOMX->enableGraphicBuffers(mNode, kPortIndexOutput, OMX_FALSE);
    return OK;
}

size_t ACodec::countBuffersOwnedByComponent(OMX_U32 portIndex) const {
    size_t n = 0;

    for (size_t i = 0; i < mBuffers[portIndex].size(); ++i) {
        const BufferInfo &info = mBuffers[portIndex].itemAt(i);

        if (info.mStatus == BufferInfo::OWNED_BY_COMPONENT) {
            ++n;
        }
    }

    return n;
}

bool ACodec::allYourBuffersAreBelongToUs(
        OMX_U32 portIndex) {
    for (size_t i = 0; i < mBuffers[portIndex].size(); ++i) {
        BufferInfo *info = &mBuffers[portIndex].editItemAt(i);

        if (info->mStatus != BufferInfo::OWNED_BY_US
                && info->mStatus != BufferInfo::OWNED_BY_NATIVE_WINDOW) {
            ALOGV("[%s] Buffer %p on port %ld still has status %d",
                    mComponentName.c_str(),
                    info->mBufferID, portIndex, info->mStatus);
            return false;
        }
    }

    return true;
}

bool ACodec::allYourBuffersAreBelongToUs() {
    return allYourBuffersAreBelongToUs(kPortIndexInput)
        && allYourBuffersAreBelongToUs(kPortIndexOutput);
}

void ACodec::deferMessage(const sp<AMessage> &msg) {
    bool wasEmptyBefore = mDeferredQueue.empty();
    mDeferredQueue.push_back(msg);
}

void ACodec::processDeferredMessages() {
    List<sp<AMessage> > queue = mDeferredQueue;
    mDeferredQueue.clear();

    List<sp<AMessage> >::iterator it = queue.begin();
    while (it != queue.end()) {
        onMessageReceived(*it++);
    }
}

void ACodec::sendFormatChange() {
    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kWhatOutputFormatChanged);

    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);
    def.nPortIndex = kPortIndexOutput;

    CHECK_EQ(mOMX->getParameter(
                mNode, OMX_IndexParamPortDefinition, &def, sizeof(def)),
             (status_t)OK);

    CHECK_EQ((int)def.eDir, (int)OMX_DirOutput);

    switch (def.eDomain) {
        case OMX_PortDomainVideo:
        {
            OMX_VIDEO_PORTDEFINITIONTYPE *videoDef = &def.format.video;

            notify->setString("mime", MEDIA_MIMETYPE_VIDEO_RAW);
            notify->setInt32("width", videoDef->nFrameWidth);
            notify->setInt32("height", videoDef->nFrameHeight);
            notify->setInt32("stride", videoDef->nStride);
            notify->setInt32("slice-height", videoDef->nSliceHeight);
            notify->setInt32("color-format", videoDef->eColorFormat);

            OMX_CONFIG_RECTTYPE rect;
            InitOMXParams(&rect);
            rect.nPortIndex = kPortIndexOutput;

            if (mOMX->getConfig(
                        mNode, OMX_IndexConfigCommonOutputCrop,
                        &rect, sizeof(rect)) != OK) {
                rect.nLeft = 0;
                rect.nTop = 0;
                rect.nWidth = videoDef->nFrameWidth;
                rect.nHeight = videoDef->nFrameHeight;
            }

            CHECK_GE(rect.nLeft, 0);
            CHECK_GE(rect.nTop, 0);
            CHECK_GE(rect.nWidth, 0u);
            CHECK_GE(rect.nHeight, 0u);
            CHECK_LE(rect.nLeft + rect.nWidth - 1, videoDef->nFrameWidth);
            CHECK_LE(rect.nTop + rect.nHeight - 1, videoDef->nFrameHeight);

            notify->setRect(
                    "crop",
                    rect.nLeft,
                    rect.nTop,
                    rect.nLeft + rect.nWidth - 1,
                    rect.nTop + rect.nHeight - 1);

            if (mNativeWindow != NULL) {
                android_native_rect_t crop;
                crop.left = rect.nLeft;
                crop.top = rect.nTop;
                crop.right = rect.nLeft + rect.nWidth;
                crop.bottom = rect.nTop + rect.nHeight;

                CHECK_EQ(0, native_window_set_crop(
                            mNativeWindow.get(), &crop));
            }
            break;
        }

        case OMX_PortDomainAudio:
        {
            OMX_AUDIO_PORTDEFINITIONTYPE *audioDef = &def.format.audio;
            CHECK_EQ((int)audioDef->eEncoding, (int)OMX_AUDIO_CodingPCM);

            OMX_AUDIO_PARAM_PCMMODETYPE params;
            InitOMXParams(&params);
            params.nPortIndex = kPortIndexOutput;

            CHECK_EQ(mOMX->getParameter(
                        mNode, OMX_IndexParamAudioPcm,
                        &params, sizeof(params)),
                     (status_t)OK);

            CHECK(params.nChannels == 1 || params.bInterleaved);
            CHECK_EQ(params.nBitPerSample, 16u);
            CHECK_EQ((int)params.eNumData, (int)OMX_NumericalDataSigned);
            CHECK_EQ((int)params.ePCMMode, (int)OMX_AUDIO_PCMModeLinear);

            notify->setString("mime", MEDIA_MIMETYPE_AUDIO_RAW);
            notify->setInt32("channel-count", params.nChannels);
            notify->setInt32("sample-rate", params.nSamplingRate);
            if (mEncoderDelay + mEncoderPadding) {
                size_t frameSize = params.nChannels * sizeof(int16_t);
                if (mSkipCutBuffer != NULL) {
                    size_t prevbufsize = mSkipCutBuffer->size();
                    if (prevbufsize != 0) {
                        ALOGW("Replacing SkipCutBuffer holding %d bytes", prevbufsize);
                    }
                }
                mSkipCutBuffer = new SkipCutBuffer(mEncoderDelay * frameSize,
                                                   mEncoderPadding * frameSize);
            }

            if (mChannelMaskPresent) {
                notify->setInt32("channel-mask", mChannelMask);
            }

            break;
        }

        default:
            TRESPASS();
    }

    notify->post();

    mSentFormat = true;
}

void ACodec::signalError(OMX_ERRORTYPE error, status_t internalError) {
    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", ACodec::kWhatError);
    notify->setInt32("omx-error", error);
#ifndef ANDROID_DEFAULT_CODE
// mtk80902: ALPS00442417 - porting error handler from OMXCodec
    if(error == OMX_ErrorStreamCorrupt)
    {                        
	ALOGW("OMXCodec::onEvent--OMX Error Stream Corrupt!!");
#ifdef MTK_AUDIO_APE_SUPPORT                
	// for ape error state to exit playback start.
	if(internalError == OMX_AUDIO_CodingAPE) {
	    notify->setInt32("err", internalError);
	    notify->post();
	}
        // for ape error state to exit playback end.
#endif                
	if(mIsVideoEncoder) {
	    ALOGW("OMXCodec::onEvent--Video encoder error");
	    notify->setInt32("err", ERROR_UNSUPPORTED_VIDEO);
	    notify->post();
        }
    } else if (mIsVideoDecoder && error == OMX_ErrorBadParameter) {
	ALOGW("OMXCodec::onEvent--OMX Bad Parameter!!");
	notify->setInt32("err", ERROR_UNSUPPORTED_VIDEO);
	notify->post();
    } else if (!mIsEncoder && !mIsVideoDecoder && error == OMX_ErrorBadParameter){
	ALOGW("OMXCodec::onEvent--Audio OMX Bad Parameter!!");
	notify->setInt32("err", ERROR_UNSUPPORTED_AUDIO);
	notify->post();
    } else {
	notify->setInt32("err", internalError);
	notify->post();
    }
#else
    notify->setInt32("err", internalError);
    notify->post();
#endif
}

status_t ACodec::pushBlankBuffersToNativeWindow() {
    status_t err = NO_ERROR;
    ANativeWindowBuffer* anb = NULL;
    int numBufs = 0;
    int minUndequeuedBufs = 0;

    // We need to reconnect to the ANativeWindow as a CPU client to ensure that
    // no frames get dropped by SurfaceFlinger assuming that these are video
    // frames.
    err = native_window_api_disconnect(mNativeWindow.get(),
            NATIVE_WINDOW_API_MEDIA);
    if (err != NO_ERROR) {
        ALOGE("error pushing blank frames: api_disconnect failed: %s (%d)",
                strerror(-err), -err);
        return err;
    }

    err = native_window_api_connect(mNativeWindow.get(),
            NATIVE_WINDOW_API_CPU);
    if (err != NO_ERROR) {
        ALOGE("error pushing blank frames: api_connect failed: %s (%d)",
                strerror(-err), -err);
        return err;
    }

    err = native_window_set_buffers_geometry(mNativeWindow.get(), 1, 1,
            HAL_PIXEL_FORMAT_RGBX_8888);
    if (err != NO_ERROR) {
        ALOGE("error pushing blank frames: set_buffers_geometry failed: %s (%d)",
                strerror(-err), -err);
        goto error;
    }

    err = native_window_set_usage(mNativeWindow.get(),
            GRALLOC_USAGE_SW_WRITE_OFTEN);
    if (err != NO_ERROR) {
        ALOGE("error pushing blank frames: set_usage failed: %s (%d)",
                strerror(-err), -err);
        goto error;
    }

    err = mNativeWindow->query(mNativeWindow.get(),
            NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &minUndequeuedBufs);
    if (err != NO_ERROR) {
        ALOGE("error pushing blank frames: MIN_UNDEQUEUED_BUFFERS query "
                "failed: %s (%d)", strerror(-err), -err);
        goto error;
    }

    numBufs = minUndequeuedBufs + 1;
    err = native_window_set_buffer_count(mNativeWindow.get(), numBufs);
    if (err != NO_ERROR) {
        ALOGE("error pushing blank frames: set_buffer_count failed: %s (%d)",
                strerror(-err), -err);
        goto error;
    }

    // We  push numBufs + 1 buffers to ensure that we've drawn into the same
    // buffer twice.  This should guarantee that the buffer has been displayed
    // on the screen and then been replaced, so an previous video frames are
    // guaranteed NOT to be currently displayed.
    for (int i = 0; i < numBufs + 1; i++) {
        int fenceFd = -1;
        err = native_window_dequeue_buffer_and_wait(mNativeWindow.get(), &anb);
        if (err != NO_ERROR) {
            ALOGE("error pushing blank frames: dequeueBuffer failed: %s (%d)",
                    strerror(-err), -err);
            goto error;
        }

        sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));

        // Fill the buffer with the a 1x1 checkerboard pattern ;)
        uint32_t* img = NULL;
        err = buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
        if (err != NO_ERROR) {
            ALOGE("error pushing blank frames: lock failed: %s (%d)",
                    strerror(-err), -err);
            goto error;
        }

        *img = 0;

        err = buf->unlock();
        if (err != NO_ERROR) {
            ALOGE("error pushing blank frames: unlock failed: %s (%d)",
                    strerror(-err), -err);
            goto error;
        }

        err = mNativeWindow->queueBuffer(mNativeWindow.get(),
                buf->getNativeBuffer(), -1);
        if (err != NO_ERROR) {
            ALOGE("error pushing blank frames: queueBuffer failed: %s (%d)",
                    strerror(-err), -err);
            goto error;
        }

        anb = NULL;
    }

error:

    if (err != NO_ERROR) {
        // Clean up after an error.
        if (anb != NULL) {
            mNativeWindow->cancelBuffer(mNativeWindow.get(), anb, -1);
        }

        native_window_api_disconnect(mNativeWindow.get(),
                NATIVE_WINDOW_API_CPU);
        native_window_api_connect(mNativeWindow.get(),
                NATIVE_WINDOW_API_MEDIA);

        return err;
    } else {
        // Clean up after success.
        err = native_window_api_disconnect(mNativeWindow.get(),
                NATIVE_WINDOW_API_CPU);
        if (err != NO_ERROR) {
            ALOGE("error pushing blank frames: api_disconnect failed: %s (%d)",
                    strerror(-err), -err);
            return err;
        }

        err = native_window_api_connect(mNativeWindow.get(),
                NATIVE_WINDOW_API_MEDIA);
        if (err != NO_ERROR) {
            ALOGE("error pushing blank frames: api_connect failed: %s (%d)",
                    strerror(-err), -err);
            return err;
        }

        return NO_ERROR;
    }
}

////////////////////////////////////////////////////////////////////////////////

ACodec::PortDescription::PortDescription() {
}

status_t ACodec::requestIDRFrame() {
    if (!mIsEncoder) {
        return ERROR_UNSUPPORTED;
    }

#ifndef ANDROID_DEFAULT_CODE
        if (!strncmp(mComponentName.c_str(), "OMX.MTK.", 8)) {
	     ALOGI("requst I frame");
            OMX_INDEXTYPE index;
            status_t err =
            mOMX->getExtensionIndex(
                    mNode,
                    "OMX.MTK.index.param.video.EncSetForceIframe",
                    &index);

            if (err != OK) {
                return err;
            }

            OMX_BOOL enable = OMX_TRUE;
            err = mOMX->setConfig(mNode, index, &enable, sizeof(enable));

            if (err != OK) {
                ALOGE("setConfig('OMX.MTK.index.param.video.EncSetForceIframe') returned error 0x%08x", err);
                return err;
            }
        }
        return OK;
#else
    OMX_CONFIG_INTRAREFRESHVOPTYPE params;
    InitOMXParams(&params);

    params.nPortIndex = kPortIndexOutput;
    params.IntraRefreshVOP = OMX_TRUE;

    return mOMX->setConfig(
            mNode,
            OMX_IndexConfigVideoIntraVOPRefresh,
            &params,
            sizeof(params));
#endif

}

#ifndef ANDROID_DEFAULT_CODE
status_t ACodec::setVEncIInterval(int seconds) {
    if (!mIsEncoder) {
        return ERROR_UNSUPPORTED;
    }
    if (!strncmp(mComponentName.c_str(), "OMX.MTK.VIDEO.ENCODER", 21)) {
	    ALOGI("set I frame rate");
        OMX_INDEXTYPE index;
        status_t err =
        mOMX->getExtensionIndex(
                mNode,
                "OMX.MTK.index.param.video.EncSetIFrameRate",
                &index);

        if (err != OK) {
            return err;
        }

        OMX_BOOL enable = OMX_TRUE;
        err = mOMX->setConfig(mNode, index, &seconds, sizeof(seconds));

        if (err != OK) {
            ALOGE("setConfig('OMX.MTK.index.param.video.EncSetIFrameRate') returned error 0x%08x", err);
            return err;
        }
    }
    return OK;
}
#endif//ANDROID_DEFAULT_CODE
void ACodec::PortDescription::addBuffer(
        IOMX::buffer_id id, const sp<ABuffer> &buffer) {
    mBufferIDs.push_back(id);
    mBuffers.push_back(buffer);
}

size_t ACodec::PortDescription::countBuffers() {
    return mBufferIDs.size();
}

IOMX::buffer_id ACodec::PortDescription::bufferIDAt(size_t index) const {
    return mBufferIDs.itemAt(index);
}

sp<ABuffer> ACodec::PortDescription::bufferAt(size_t index) const {
    return mBuffers.itemAt(index);
}

////////////////////////////////////////////////////////////////////////////////

ACodec::BaseState::BaseState(ACodec *codec, const sp<AState> &parentState)
    : AState(parentState),
      mCodec(codec) {
}

ACodec::BaseState::PortMode ACodec::BaseState::getPortMode(OMX_U32 portIndex) {
    return KEEP_BUFFERS;
}

bool ACodec::BaseState::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatInputBufferFilled:
        {
            onInputBufferFilled(msg);
            break;
        }

        case kWhatOutputBufferDrained:
        {
            onOutputBufferDrained(msg);
            break;
        }

        case ACodec::kWhatOMXMessage:
        {
            return onOMXMessage(msg);
        }

        default:
            return false;
    }

    return true;
}

bool ACodec::BaseState::onOMXMessage(const sp<AMessage> &msg) {
    int32_t type;
    CHECK(msg->findInt32("type", &type));

    IOMX::node_id nodeID;
    CHECK(msg->findPointer("node", &nodeID));
    CHECK_EQ(nodeID, mCodec->mNode);

    switch (type) {
        case omx_message::EVENT:
        {
            int32_t event, data1, data2;
            CHECK(msg->findInt32("event", &event));
            CHECK(msg->findInt32("data1", &data1));
            CHECK(msg->findInt32("data2", &data2));

            if (event == OMX_EventCmdComplete
                    && data1 == OMX_CommandFlush
                    && data2 == (int32_t)OMX_ALL) {
                // Use of this notification is not consistent across
                // implementations. We'll drop this notification and rely
                // on flush-complete notifications on the individual port
                // indices instead.

                return true;
            }

            return onOMXEvent(
                    static_cast<OMX_EVENTTYPE>(event),
                    static_cast<OMX_U32>(data1),
                    static_cast<OMX_U32>(data2));
        }

        case omx_message::EMPTY_BUFFER_DONE:
        {
            IOMX::buffer_id bufferID;
            CHECK(msg->findPointer("buffer", &bufferID));

            return onOMXEmptyBufferDone(bufferID);
        }

        case omx_message::FILL_BUFFER_DONE:
        {
            IOMX::buffer_id bufferID;
            CHECK(msg->findPointer("buffer", &bufferID));

            int32_t rangeOffset, rangeLength, flags;
            int64_t timeUs;
            void *platformPrivate;
            void *dataPtr;

            CHECK(msg->findInt32("range_offset", &rangeOffset));
            CHECK(msg->findInt32("range_length", &rangeLength));
            CHECK(msg->findInt32("flags", &flags));
            CHECK(msg->findInt64("timestamp", &timeUs));
            CHECK(msg->findPointer("platform_private", &platformPrivate));
            CHECK(msg->findPointer("data_ptr", &dataPtr));

            return onOMXFillBufferDone(
                    bufferID,
                    (size_t)rangeOffset, (size_t)rangeLength,
                    (OMX_U32)flags,
                    timeUs,
                    platformPrivate,
                    dataPtr);
        }

        default:
            TRESPASS();
            break;
    }
}

bool ACodec::BaseState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    if (event != OMX_EventError) {
        ALOGV("[%s] EVENT(%d, 0x%08lx, 0x%08lx)",
             mCodec->mComponentName.c_str(), event, data1, data2);

        return false;
    }

    ALOGE("[%s] ERROR(0x%08lx)", mCodec->mComponentName.c_str(), data1);

    mCodec->signalError((OMX_ERRORTYPE)data1);

    return true;
}

bool ACodec::BaseState::onOMXEmptyBufferDone(IOMX::buffer_id bufferID) {
    ALOGV("[%s] onOMXEmptyBufferDone %p",
         mCodec->mComponentName.c_str(), bufferID);

    BufferInfo *info =
        mCodec->findBufferByID(kPortIndexInput, bufferID);

    CHECK_EQ((int)info->mStatus, (int)BufferInfo::OWNED_BY_COMPONENT);
    info->mStatus = BufferInfo::OWNED_BY_US;

    const sp<AMessage> &bufferMeta = info->mData->meta();
    void *mediaBuffer;
    if (bufferMeta->findPointer("mediaBuffer", &mediaBuffer)
            && mediaBuffer != NULL) {
        // We're in "store-metadata-in-buffers" mode, the underlying
        // OMX component had access to data that's implicitly refcounted
        // by this "mediaBuffer" object. Now that the OMX component has
        // told us that it's done with the input buffer, we can decrement
        // the mediaBuffer's reference count.
      
        
        ((MediaBuffer *)mediaBuffer)->release();

	    if (!strncmp(mCodec->mComponentName.c_str(), "OMX.MTK.VIDEO.ENCODER", 21)) {
       		  ALOGI("[video buffer]onOMXEmptyBufferDone  :  releasing mediaBuffer %p,refcount after release=%d", 
		 	                                                              mediaBuffer,((MediaBuffer *)mediaBuffer)->refcount() );
          }

	   
        mediaBuffer = NULL;

        bufferMeta->setPointer("mediaBuffer", NULL);
    }

 
    PortMode mode = getPortMode(kPortIndexInput);

    switch (mode) {
        case KEEP_BUFFERS:
            break;

        case RESUBMIT_BUFFERS:
#ifndef ANDROID_DEFAULT_CODE
	// mtk80902: porting from AwesomePlayer: prevent buffering twice
	    if (mCodec->mMaxQueueBufferNum > 0) {
		size_t n = mCodec->mBuffers[kPortIndexInput].size();
		size_t others = 0;
		for (size_t i = 0; i < n; ++i) {
		    BufferInfo *info = &mCodec->mBuffers[kPortIndexInput].editItemAt(i);
		    if (info->mStatus == BufferInfo::OWNED_BY_COMPONENT)
			others++;
		}

		if (mCodec->mMaxQueueBufferNum < others) {
		    ALOGV("mMaxQueueBufferNum %d < component occupied %d, wait for next trigger.",
			    mCodec->mMaxQueueBufferNum, others);
		    break;
		}
	    }
#endif
            postFillThisBuffer(info);
            break;

        default:
        {
            CHECK_EQ((int)mode, (int)FREE_BUFFERS);
            TRESPASS();  // Not currently used
            break;
        }
    }

    return true;
}

void ACodec::BaseState::postFillThisBuffer(BufferInfo *info) {
    if (mCodec->mPortEOS[kPortIndexInput]) {
        return;
    }

    CHECK_EQ((int)info->mStatus, (int)BufferInfo::OWNED_BY_US);

#ifndef ANDROID_DEFAULT_CODE
    if (mCodec->mLeftOverBuffer != NULL) {
        ALOGD("[%s] left over buffer (id = %p)", 
               mCodec->mComponentName.c_str(), info->mBufferID);
        info->mData->meta()->clear();

        sp<AMessage> reply = new AMessage(kWhatInputBufferFilled, mCodec->id());
        reply->setPointer("buffer-id", info->mBufferID);
        reply->setBuffer("buffer", mCodec->mLeftOverBuffer);
        mCodec->mLeftOverBuffer = NULL;
//        reply->setInt32("partial", 1);
        reply->post();

        info->mStatus = BufferInfo::OWNED_BY_UPSTREAM;
        return;
    }
#endif
    sp<AMessage> notify = mCodec->mNotify->dup();
    notify->setInt32("what", ACodec::kWhatFillThisBuffer);
    notify->setPointer("buffer-id", info->mBufferID);


#ifndef ANDROID_DEFAULT_CODE
   {
            
         void *mediaBuffer;
         if(info->mData->meta()->findPointer("mediaBuffer", &mediaBuffer)
                 && mediaBuffer != NULL){
             //ALOGI("postFillThisBuffer release mediabuffer");
             ((MediaBuffer *)mediaBuffer)->release();
         }
    info->mData->meta()->clear();
    }
#else
    info->mData->meta()->clear();
#endif


    notify->setBuffer("buffer", info->mData);

    sp<AMessage> reply = new AMessage(kWhatInputBufferFilled, mCodec->id());
    reply->setPointer("buffer-id", info->mBufferID);

    notify->setMessage("reply", reply);

    notify->post();

    info->mStatus = BufferInfo::OWNED_BY_UPSTREAM;
}

void ACodec::BaseState::onInputBufferFilled(const sp<AMessage> &msg) {
    IOMX::buffer_id bufferID;
    CHECK(msg->findPointer("buffer-id", &bufferID));

    sp<ABuffer> buffer;
    int32_t err = OK;
    bool eos = false;

    if (!msg->findBuffer("buffer", &buffer)) {
        CHECK(msg->findInt32("err", &err));

        ALOGD("[%s] saw error %d instead of an input buffer",
             mCodec->mComponentName.c_str(), err);

        buffer.clear();

        eos = true;
    }

    int32_t tmp;
    if (buffer != NULL && buffer->meta()->findInt32("eos", &tmp) && tmp) {
        eos = true;
        err = ERROR_END_OF_STREAM;
    }

    BufferInfo *info = mCodec->findBufferByID(kPortIndexInput, bufferID);
    CHECK_EQ((int)info->mStatus, (int)BufferInfo::OWNED_BY_UPSTREAM);

   /*
    if (msg->findInt32("partial", &tmp)) {
        int64_t tt;
        buffer->meta()->findInt64("timeUs", &tt);
        ALOGD("partial frame filled, %lld, %p, size = %d", tt, buffer->data(), (int)buffer->size());
        ALOGD("\t\t%p (%d %p), capacity, size = %d", bufferID,  info->mData->offset(), info->mData->data(), info->mData->capacity());
    }
    */
    info->mStatus = BufferInfo::OWNED_BY_US;

    PortMode mode = getPortMode(kPortIndexInput);
#ifndef ANDROID_DEFAULT_CODE
#ifdef DUMP_BITSTREAM
    if (buffer != NULL) {
        int64_t tt;
        int32_t isCSD = false;
        buffer->meta()->findInt64("timeUs", &tt);
        ALOGD("[%s]buffer to be empty, %lld, %p, size = %d", mCodec->mComponentName.c_str(), tt, buffer->data(), (int)buffer->size());
        buffer->meta()->findInt32("csd", &isCSD) ;
        if (buffer->size() >= 4) {
            ALOGD("[%s]\t\t %s, %02x %02x %02x %02x", 
                    mCodec->mComponentName.c_str(), 
                    isCSD ? "codec_cfg":"", 
                    buffer->data()[0], buffer->data()[1] , buffer->data()[2] , buffer->data()[3]);
        }

        if ((mCodec->mDumpFile != NULL) && 
                (!strcmp(mCodec->mComponentName.c_str(), "OMX.MTK.VIDEO.DECODER.AVC"))) {
            if (!isCSD) {
                char nal_prefix[] = {0, 0, 0, 1};
                fwrite(nal_prefix, 1, 4, mCodec->mDumpFile);
            }
            size_t nWrite = fwrite(buffer->data(), 1, buffer->size(), mCodec->mDumpFile);
            ALOGD("written %d bytes, ftell = %d", nWrite, (int)ftell(mCodec->mDumpFile));
        }
    }
#endif
#endif
    switch (mode) {
        case KEEP_BUFFERS:
        {
            if (eos) {
                if (!mCodec->mPortEOS[kPortIndexInput]) {
                    mCodec->mPortEOS[kPortIndexInput] = true;
                    mCodec->mInputEOSResult = err;
                }
            }
            break;
        }

        case RESUBMIT_BUFFERS:
        {
            if (buffer != NULL && !mCodec->mPortEOS[kPortIndexInput]) {
                int64_t timeUs;
                CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

                OMX_U32 flags = OMX_BUFFERFLAG_ENDOFFRAME;

                int32_t isCSD;
                if (buffer->meta()->findInt32("csd", &isCSD) && isCSD != 0) {
#ifndef ANDROID_DEFAULT_CODE
		    ALOGI("received csd settings.");
#endif
                    flags |= OMX_BUFFERFLAG_CODECCONFIG;
                }

                if (eos) {
                    flags |= OMX_BUFFERFLAG_EOS;
                }

                if (buffer != info->mData) {
                    ALOGV("[%s] Needs to copy input data for buffer %p. (%p != %p)",
                         mCodec->mComponentName.c_str(),
                         bufferID,
                         buffer.get(), info->mData.get());
#ifndef ANDROID_DEFAULT_CODE
                    int capacity = info->mData->capacity();
                    if (buffer->size() > capacity) {
                        if (mCodec->mSupportsPartialFrames) {
                            sp<ABuffer> leftBuffer = new ABuffer(buffer->size() - capacity);
                            memcpy(leftBuffer->data(), buffer->data() + capacity, buffer->size() - capacity);
                            leftBuffer->meta()->setInt64("timeUs", timeUs);
                            if (isCSD) {
                                leftBuffer->meta()->setInt32("csd", isCSD);
                            }

                            ALOGI("[%s] split big input buffer %d to %d + %d",
                                    mCodec->mComponentName.c_str(),  buffer->size(), capacity, leftBuffer->size());

                            buffer->setRange(buffer->offset(), capacity);
                            flags &= ~OMX_BUFFERFLAG_ENDOFFRAME;

                            mCodec->mLeftOverBuffer = leftBuffer;
                        } else {
                            ALOGE("Codec's input buffers are too small to accomodate "
                                    " buffer read from source (info->mSize = %d, srcLength = %d)",
                                    info->mData->capacity(), buffer->size());
                            mCodec->signalError();
                            break;
                            //CHECK_LE(buffer->size(), info->mData->capacity());
                        }
                    }
#else
                    CHECK_LE(buffer->size(), info->mData->capacity());
#endif
                    memcpy(info->mData->data(), buffer->data(), buffer->size());
                }

                if (flags & OMX_BUFFERFLAG_CODECCONFIG) {
                    ALOGV("[%s] calling emptyBuffer %p w/ codec specific data",
                         mCodec->mComponentName.c_str(), bufferID);
                } else if (flags & OMX_BUFFERFLAG_EOS) {
                    ALOGV("[%s] calling emptyBuffer %p w/ EOS",
                         mCodec->mComponentName.c_str(), bufferID);
                } else {
#if TRACK_BUFFER_TIMING
                    ALOGI("[%s] calling emptyBuffer %p w/ time %lld us",
                         mCodec->mComponentName.c_str(), bufferID, timeUs);
#else
                    ALOGV("[%s] calling emptyBuffer %p w/ time %lld us",
                         mCodec->mComponentName.c_str(), bufferID, timeUs);
#endif
                }

#if TRACK_BUFFER_TIMING
                ACodec::BufferStats stats;
                stats.mEmptyBufferTimeUs = ALooper::GetNowUs();
                stats.mFillBufferDoneTimeUs = -1ll;
                mCodec->mBufferStats.add(timeUs, stats);
#endif


#if 0
    void *mediaBuffer=NULL;
    if (buffer->meta()->findPointer("mediaBuffer", &mediaBuffer)
             && mediaBuffer != NULL) {
       if (!strncmp(mCodec->mComponentName.c_str(), "OMX.MTK.VIDEO.ENCODER", 21)) 
        ALOGV("[video buffer]onInputBufferFilled  :  empty mediaBuffer %p to omx", mediaBuffer);
    }
#endif

                CHECK_EQ(mCodec->mOMX->emptyBuffer(
                            mCodec->mNode,
                            bufferID,
                            0,
                            buffer->size(),
                            flags,
                            timeUs),
                         (status_t)OK);

                info->mStatus = BufferInfo::OWNED_BY_COMPONENT;

                if (!eos) {
                    getMoreInputDataIfPossible();
                } else {
                    ALOGV("[%s] Signalled EOS on the input port",
                         mCodec->mComponentName.c_str());

                    mCodec->mPortEOS[kPortIndexInput] = true;
                    mCodec->mInputEOSResult = err;
                }
            } else if (!mCodec->mPortEOS[kPortIndexInput]) {
                if (err != ERROR_END_OF_STREAM) {
                    ALOGV("[%s] Signalling EOS on the input port "
                         "due to error %d",
                         mCodec->mComponentName.c_str(), err);
                } else {
                    ALOGV("[%s] Signalling EOS on the input port",
                         mCodec->mComponentName.c_str());
                }

                ALOGV("[%s] calling emptyBuffer %p signalling EOS",
                     mCodec->mComponentName.c_str(), bufferID);

                CHECK_EQ(mCodec->mOMX->emptyBuffer(
                            mCodec->mNode,
                            bufferID,
                            0,
                            0,
                            OMX_BUFFERFLAG_EOS,
                            0),
                         (status_t)OK);

                info->mStatus = BufferInfo::OWNED_BY_COMPONENT;

                mCodec->mPortEOS[kPortIndexInput] = true;
                mCodec->mInputEOSResult = err;
            }
            break;

            default:
                CHECK_EQ((int)mode, (int)FREE_BUFFERS);
                break;
        }
    }
}

void ACodec::BaseState::getMoreInputDataIfPossible() {
    if (mCodec->mPortEOS[kPortIndexInput]) {
        return;
    }

    BufferInfo *eligible = NULL;

    for (size_t i = 0; i < mCodec->mBuffers[kPortIndexInput].size(); ++i) {
        BufferInfo *info = &mCodec->mBuffers[kPortIndexInput].editItemAt(i);

#if 0
        if (info->mStatus == BufferInfo::OWNED_BY_UPSTREAM) {
            // There's already a "read" pending.
            return;
        }
#endif

        if (info->mStatus == BufferInfo::OWNED_BY_US) {
            eligible = info;
        }
    }

    if (eligible == NULL) {
        return;
    }

    postFillThisBuffer(eligible);
}

bool ACodec::BaseState::onOMXFillBufferDone(
        IOMX::buffer_id bufferID,
        size_t rangeOffset, size_t rangeLength,
        OMX_U32 flags,
        int64_t timeUs,
        void *platformPrivate,
        void *dataPtr) {
    ALOGV("[%s] onOMXFillBufferDone %p time %lld us, flags = 0x%08lx",
         mCodec->mComponentName.c_str(), bufferID, timeUs, flags);

    ssize_t index;

#if TRACK_BUFFER_TIMING
    index = mCodec->mBufferStats.indexOfKey(timeUs);
    if (index >= 0) {
        ACodec::BufferStats *stats = &mCodec->mBufferStats.editValueAt(index);
        stats->mFillBufferDoneTimeUs = ALooper::GetNowUs();

        ALOGI("frame PTS %lld: %lld",
                timeUs,
                stats->mFillBufferDoneTimeUs - stats->mEmptyBufferTimeUs);

        mCodec->mBufferStats.removeItemsAt(index);
        stats = NULL;
    }
#endif

    BufferInfo *info =
        mCodec->findBufferByID(kPortIndexOutput, bufferID, &index);

    CHECK_EQ((int)info->mStatus, (int)BufferInfo::OWNED_BY_COMPONENT);

    info->mStatus = BufferInfo::OWNED_BY_US;

    PortMode mode = getPortMode(kPortIndexOutput);

    switch (mode) {
        case KEEP_BUFFERS:
            break;

        case RESUBMIT_BUFFERS:
        {
            if (rangeLength == 0 && !(flags & OMX_BUFFERFLAG_EOS)) {
                ALOGV("[%s] calling fillBuffer %p",
                     mCodec->mComponentName.c_str(), info->mBufferID);
#ifndef ANDROID_DEFAULT_CODE
                if (mCodec->mPortEOS[kPortIndexOutput])
                {//Bruce 2013/01/21 if after eos, we don't send fill_this_buffer again, or it may cause busy loop on Mtk Omx component
                    ALOGE("Output already EOS!");
                    break;
                }
#endif//ANDROID_DEFAULT_CODE
                CHECK_EQ(mCodec->mOMX->fillBuffer(
                            mCodec->mNode, info->mBufferID),
                         (status_t)OK);

                info->mStatus = BufferInfo::OWNED_BY_COMPONENT;
                break;
            }

            if (!mCodec->mIsEncoder && !mCodec->mSentFormat) {
                mCodec->sendFormatChange();
            }

            if (mCodec->mNativeWindow == NULL) {
                info->mData->setRange(rangeOffset, rangeLength);

#if 0
                if (IsIDR(info->mData)) {
                    ALOGI("IDR frame");
            }
#endif
            }

            if (mCodec->mSkipCutBuffer != NULL) {
                mCodec->mSkipCutBuffer->submit(info->mData);
            }
            info->mData->meta()->setInt64("timeUs", timeUs);

            sp<AMessage> notify = mCodec->mNotify->dup();
            notify->setInt32("what", ACodec::kWhatDrainThisBuffer);
            notify->setPointer("buffer-id", info->mBufferID);
            notify->setBuffer("buffer", info->mData);
            notify->setInt32("flags", flags);

            sp<AMessage> reply =
                new AMessage(kWhatOutputBufferDrained, mCodec->id());

            reply->setPointer("buffer-id", info->mBufferID);

            notify->setMessage("reply", reply);

            notify->post();

            info->mStatus = BufferInfo::OWNED_BY_DOWNSTREAM;

            if (flags & OMX_BUFFERFLAG_EOS) {
                ALOGV("[%s] saw output EOS", mCodec->mComponentName.c_str());

                sp<AMessage> notify = mCodec->mNotify->dup();
                notify->setInt32("what", ACodec::kWhatEOS);
                notify->setInt32("err", mCodec->mInputEOSResult);
                notify->post();

                mCodec->mPortEOS[kPortIndexOutput] = true;
            }
            break;
        }

        default:
        {
            CHECK_EQ((int)mode, (int)FREE_BUFFERS);

            CHECK_EQ((status_t)OK,
                     mCodec->freeBuffer(kPortIndexOutput, index));
            break;
        }
    }

    return true;
}

void ACodec::BaseState::onOutputBufferDrained(const sp<AMessage> &msg) {
    IOMX::buffer_id bufferID;
    CHECK(msg->findPointer("buffer-id", &bufferID));

    ssize_t index;
    BufferInfo *info =
        mCodec->findBufferByID(kPortIndexOutput, bufferID, &index);
    CHECK_EQ((int)info->mStatus, (int)BufferInfo::OWNED_BY_DOWNSTREAM);

    int32_t render;
    if (mCodec->mNativeWindow != NULL
            && msg->findInt32("render", &render) && render != 0) {
        // The client wants this buffer to be rendered.

        status_t err;
        if ((err = mCodec->mNativeWindow->queueBuffer(
                    mCodec->mNativeWindow.get(),
                    info->mGraphicBuffer.get(), -1)) == OK) {
            info->mStatus = BufferInfo::OWNED_BY_NATIVE_WINDOW;
        } else {
            mCodec->signalError(OMX_ErrorUndefined, err);
            info->mStatus = BufferInfo::OWNED_BY_US;
        }
    } else {
        info->mStatus = BufferInfo::OWNED_BY_US;
    }

    PortMode mode = getPortMode(kPortIndexOutput);

    switch (mode) {
        case KEEP_BUFFERS:
        {
            // XXX fishy, revisit!!! What about the FREE_BUFFERS case below?

            if (info->mStatus == BufferInfo::OWNED_BY_NATIVE_WINDOW) {
                // We cannot resubmit the buffer we just rendered, dequeue
                // the spare instead.

                info = mCodec->dequeueBufferFromNativeWindow();
            }
            break;
        }

        case RESUBMIT_BUFFERS:
        {
            if (!mCodec->mPortEOS[kPortIndexOutput]) {
                if (info->mStatus == BufferInfo::OWNED_BY_NATIVE_WINDOW) {
                    // We cannot resubmit the buffer we just rendered, dequeue
                    // the spare instead.

                    info = mCodec->dequeueBufferFromNativeWindow();
                }

                if (info != NULL) {
                    ALOGV("[%s] calling fillBuffer %p",
                         mCodec->mComponentName.c_str(), info->mBufferID);

                    CHECK_EQ(mCodec->mOMX->fillBuffer(mCodec->mNode, info->mBufferID),
                             (status_t)OK);

                    info->mStatus = BufferInfo::OWNED_BY_COMPONENT;
                }
            }
            break;
        }

        default:
        {
            CHECK_EQ((int)mode, (int)FREE_BUFFERS);

            CHECK_EQ((status_t)OK,
                     mCodec->freeBuffer(kPortIndexOutput, index));
            break;
        }
    }
}

////////////////////////////////////////////////////////////////////////////////

ACodec::UninitializedState::UninitializedState(ACodec *codec)
    : BaseState(codec) {
}

void ACodec::UninitializedState::stateEntered() {
    ALOGD("Now uninitialized");
}

bool ACodec::UninitializedState::onMessageReceived(const sp<AMessage> &msg) {
    bool handled = false;

    switch (msg->what()) {
        case ACodec::kWhatSetup:
        {
            onSetup(msg);

            handled = true;
            break;
        }

        case ACodec::kWhatAllocateComponent:
        {
            onAllocateComponent(msg);
            handled = true;
            break;
        }

        case ACodec::kWhatShutdown:
        {
            int32_t keepComponentAllocated;
            CHECK(msg->findInt32(
                        "keepComponentAllocated", &keepComponentAllocated));
            CHECK(!keepComponentAllocated);

            sp<AMessage> notify = mCodec->mNotify->dup();
            notify->setInt32("what", ACodec::kWhatShutdownCompleted);
            notify->post();

            handled = true;
            break;
        }

        case ACodec::kWhatFlush:
        {
            sp<AMessage> notify = mCodec->mNotify->dup();
            notify->setInt32("what", ACodec::kWhatFlushCompleted);
            notify->post();

            handled = true;
            break;
        }

        default:
            return BaseState::onMessageReceived(msg);
    }

    return handled;
}

void ACodec::UninitializedState::onSetup(
        const sp<AMessage> &msg) {
#ifndef ANDROID_DEFAULT_CODE
    int32_t bAutoRun = 1;
    if (!msg->findInt32("auto-run", &bAutoRun)) {
        bAutoRun = 1;        
    }
    ALOGD("auto run = %d", (int32_t)bAutoRun);
#endif
    if (onAllocateComponent(msg)
            && mCodec->mLoadedState->onConfigureComponent(msg)
#ifndef ANDROID_DEFAULT_CODE
            && (bAutoRun)
#endif
            ) {
        ALOGD("start immediately after config component ");
        mCodec->mLoadedState->onStart();
    }
}

bool ACodec::UninitializedState::onAllocateComponent(const sp<AMessage> &msg) {
    ALOGV("onAllocateComponent");

    CHECK(mCodec->mNode == NULL);

    OMXClient client;
    CHECK_EQ(client.connect(), (status_t)OK);

    sp<IOMX> omx = client.interface();

    Vector<OMXCodec::CodecNameAndQuirks> matchingCodecs;

    AString mime;

    AString componentName;
    uint32_t quirks = 0;
    if (msg->findString("componentName", &componentName)) {
        ssize_t index = matchingCodecs.add();
        OMXCodec::CodecNameAndQuirks *entry = &matchingCodecs.editItemAt(index);
        entry->mName = String8(componentName.c_str());

        if (!OMXCodec::findCodecQuirks(
                    componentName.c_str(), &entry->mQuirks)) {
            entry->mQuirks = 0;
        }
    } else {
        CHECK(msg->findString("mime", &mime));

        int32_t encoder;
        if (!msg->findInt32("encoder", &encoder)) {
            encoder = false;
        }

        OMXCodec::findMatchingCodecs(
                mime.c_str(),
                encoder, // createEncoder
                NULL,  // matchComponentName
                0,     // flags
                &matchingCodecs);
    }

    sp<CodecObserver> observer = new CodecObserver;
    IOMX::node_id node = NULL;

    for (size_t matchIndex = 0; matchIndex < matchingCodecs.size();
            ++matchIndex) {
        componentName = matchingCodecs.itemAt(matchIndex).mName.string();
        quirks = matchingCodecs.itemAt(matchIndex).mQuirks;

        pid_t tid = androidGetTid();
        int prevPriority = androidGetThreadPriority(tid);
        androidSetThreadPriority(tid, ANDROID_PRIORITY_FOREGROUND);
        status_t err = omx->allocateNode(componentName.c_str(), observer, &node);
        androidSetThreadPriority(tid, prevPriority);

        if (err == OK) {
            break;
        }

        node = NULL;
    }

    if (node == NULL) {
        if (!mime.empty()) {
            ALOGE("Unable to instantiate a decoder for type '%s'.",
                 mime.c_str());
        } else {
            ALOGE("Unable to instantiate decoder '%s'.", componentName.c_str());
        }

        mCodec->signalError(OMX_ErrorComponentNotFound);
        return false;
    }

    sp<AMessage> notify = new AMessage(kWhatOMXMessage, mCodec->id());
    observer->setNotificationMessage(notify);

    mCodec->mComponentName = componentName;
    mCodec->mFlags = 0;

    if (componentName.endsWith(".secure")) {
        mCodec->mFlags |= kFlagIsSecure;
    }

    mCodec->mQuirks = quirks;
    mCodec->mOMX = omx;
    mCodec->mNode = node;

    mCodec->mPortEOS[kPortIndexInput] =
        mCodec->mPortEOS[kPortIndexOutput] = false;

    mCodec->mInputEOSResult = OK;

    {
        sp<AMessage> notify = mCodec->mNotify->dup();
        notify->setInt32("what", ACodec::kWhatComponentAllocated);
        notify->setString("componentName", mCodec->mComponentName.c_str());
#ifndef ANDROID_DEFAULT_CODE
	notify->setInt32("quirks", quirks);
#endif
        notify->post();
    }

    mCodec->changeState(mCodec->mLoadedState);

    return true;
}

////////////////////////////////////////////////////////////////////////////////

ACodec::LoadedState::LoadedState(ACodec *codec)
    : BaseState(codec) {
}

void ACodec::LoadedState::stateEntered() {
    ALOGD("[%s] Now Loaded", mCodec->mComponentName.c_str());

    if (mCodec->mShutdownInProgress) {
        bool keepComponentAllocated = mCodec->mKeepComponentAllocated;

        mCodec->mShutdownInProgress = false;
        mCodec->mKeepComponentAllocated = false;

        onShutdown(keepComponentAllocated);
    }
}


void ACodec::LoadedState::onShutdown(bool keepComponentAllocated) {
    if (!keepComponentAllocated) {
        CHECK_EQ(mCodec->mOMX->freeNode(mCodec->mNode), (status_t)OK);

        mCodec->mNativeWindow.clear();
        mCodec->mNode = NULL;
        mCodec->mOMX.clear();
        mCodec->mQuirks = 0;
        mCodec->mFlags = 0;
        mCodec->mComponentName.clear();

        mCodec->changeState(mCodec->mUninitializedState);
    }

    sp<AMessage> notify = mCodec->mNotify->dup();
    notify->setInt32("what", ACodec::kWhatShutdownCompleted);
    notify->post();

}

bool ACodec::LoadedState::onMessageReceived(const sp<AMessage> &msg) {
    bool handled = false;

    switch (msg->what()) {
        case ACodec::kWhatConfigureComponent:
        {
            onConfigureComponent(msg);
            handled = true;
            break;
        }

        case ACodec::kWhatStart:
        {
            onStart();
            handled = true;
            break;
        }

        case ACodec::kWhatShutdown:
        {
            int32_t keepComponentAllocated;
            CHECK(msg->findInt32(
                        "keepComponentAllocated", &keepComponentAllocated));

            onShutdown(keepComponentAllocated);

            handled = true;
            break;
        }

        case ACodec::kWhatFlush:
        {
            sp<AMessage> notify = mCodec->mNotify->dup();
            notify->setInt32("what", ACodec::kWhatFlushCompleted);
            notify->post();

            handled = true;
            break;
        }

        default:
            return BaseState::onMessageReceived(msg);
    }

    return handled;
}

bool ACodec::LoadedState::onConfigureComponent(
        const sp<AMessage> &msg) {
    ALOGV("onConfigureComponent");

    CHECK(mCodec->mNode != NULL);

    AString mime;
    CHECK(msg->findString("mime", &mime));

    status_t err = mCodec->configureCodec(mime.c_str(), msg);

    if (err != OK) {
        ALOGE("[%s] configureCodec returning error %d",
              mCodec->mComponentName.c_str(), err);

        mCodec->signalError(OMX_ErrorUndefined, err);
        return false;
    }

    sp<RefBase> obj;
    if (msg->findObject("native-window", &obj)
            && strncmp("OMX.google.", mCodec->mComponentName.c_str(), 11)) {
        sp<NativeWindowWrapper> nativeWindow(
                static_cast<NativeWindowWrapper *>(obj.get()));
        CHECK(nativeWindow != NULL);
        mCodec->mNativeWindow = nativeWindow->getNativeWindow();

        native_window_set_scaling_mode(
                mCodec->mNativeWindow.get(),
                NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
    }
    CHECK_EQ((status_t)OK, mCodec->initNativeWindow());

    {
        sp<AMessage> notify = mCodec->mNotify->dup();
        notify->setInt32("what", ACodec::kWhatComponentConfigured);
        notify->post();
    }

    return true;
}

void ACodec::LoadedState::onStart() {
    ALOGV("onStart");

    CHECK_EQ(mCodec->mOMX->sendCommand(
                mCodec->mNode, OMX_CommandStateSet, OMX_StateIdle),
             (status_t)OK);

    mCodec->changeState(mCodec->mLoadedToIdleState);
}

////////////////////////////////////////////////////////////////////////////////

ACodec::LoadedToIdleState::LoadedToIdleState(ACodec *codec)
    : BaseState(codec) {
}

void ACodec::LoadedToIdleState::stateEntered() {
    ALOGD("[%s] Now Loaded->Idle", mCodec->mComponentName.c_str());

    status_t err;
    if ((err = allocateBuffers()) != OK) {
        ALOGE("Failed to allocate buffers after transitioning to IDLE state "
             "(error 0x%08x)",
             err);

        mCodec->signalError(OMX_ErrorUndefined, err);

        mCodec->changeState(mCodec->mLoadedState);
    }
}

status_t ACodec::LoadedToIdleState::allocateBuffers() {
    status_t err = mCodec->allocateBuffersOnPort(kPortIndexInput);

    if (err != OK) {
        return err;
    }

    return mCodec->allocateBuffersOnPort(kPortIndexOutput);
}

bool ACodec::LoadedToIdleState::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
#ifndef ANDROID_DEFAULT_CODE
        case kWhatFlush:
#endif
        case kWhatShutdown:
        {
            mCodec->deferMessage(msg);
            return true;
        }

        default:
            return BaseState::onMessageReceived(msg);
    }
}

bool ACodec::LoadedToIdleState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    switch (event) {
        case OMX_EventCmdComplete:
        {
            CHECK_EQ(data1, (OMX_U32)OMX_CommandStateSet);
            CHECK_EQ(data2, (OMX_U32)OMX_StateIdle);

            CHECK_EQ(mCodec->mOMX->sendCommand(
                        mCodec->mNode, OMX_CommandStateSet, OMX_StateExecuting),
                     (status_t)OK);

            mCodec->changeState(mCodec->mIdleToExecutingState);

            return true;
        }

        default:
            return BaseState::onOMXEvent(event, data1, data2);
    }
}

////////////////////////////////////////////////////////////////////////////////

ACodec::IdleToExecutingState::IdleToExecutingState(ACodec *codec)
    : BaseState(codec) {
}

void ACodec::IdleToExecutingState::stateEntered() {
    ALOGD("[%s] Now Idle->Executing", mCodec->mComponentName.c_str());
}

bool ACodec::IdleToExecutingState::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
#ifndef ANDROID_DEFAULT_CODE
        case kWhatFlush:
#endif
        case kWhatShutdown:
        {
            mCodec->deferMessage(msg);
            return true;
        }

        default:
            return BaseState::onMessageReceived(msg);
    }
}

bool ACodec::IdleToExecutingState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    switch (event) {
        case OMX_EventCmdComplete:
        {
            CHECK_EQ(data1, (OMX_U32)OMX_CommandStateSet);
            CHECK_EQ(data2, (OMX_U32)OMX_StateExecuting);

            mCodec->mExecutingState->resume();
            mCodec->changeState(mCodec->mExecutingState);

            return true;
        }

        default:
            return BaseState::onOMXEvent(event, data1, data2);
    }
}

////////////////////////////////////////////////////////////////////////////////

ACodec::ExecutingState::ExecutingState(ACodec *codec)
    : BaseState(codec),
      mActive(false) {
}

ACodec::BaseState::PortMode ACodec::ExecutingState::getPortMode(
        OMX_U32 portIndex) {
    return RESUBMIT_BUFFERS;
}

void ACodec::ExecutingState::submitOutputBuffers() {
    for (size_t i = 0; i < mCodec->mBuffers[kPortIndexOutput].size(); ++i) {
        BufferInfo *info = &mCodec->mBuffers[kPortIndexOutput].editItemAt(i);

        if (mCodec->mNativeWindow != NULL) {
            CHECK(info->mStatus == BufferInfo::OWNED_BY_US
                    || info->mStatus == BufferInfo::OWNED_BY_NATIVE_WINDOW);

            if (info->mStatus == BufferInfo::OWNED_BY_NATIVE_WINDOW) {
                continue;
            }

        } else {
            CHECK_EQ((int)info->mStatus, (int)BufferInfo::OWNED_BY_US);
        }

        ALOGV("[%s] calling fillBuffer %p",
             mCodec->mComponentName.c_str(), info->mBufferID);

        CHECK_EQ(mCodec->mOMX->fillBuffer(mCodec->mNode, info->mBufferID),
                 (status_t)OK);

        info->mStatus = BufferInfo::OWNED_BY_COMPONENT;
    }
}

void ACodec::ExecutingState::resume() {
    if (mActive) {
        ALOGV("[%s] We're already active, no need to resume.",
             mCodec->mComponentName.c_str());

        return;
    }

    submitOutputBuffers();

    // Post the first input buffer.
    CHECK_GT(mCodec->mBuffers[kPortIndexInput].size(), 0u);
    BufferInfo *info = &mCodec->mBuffers[kPortIndexInput].editItemAt(0);

    postFillThisBuffer(info);

    mActive = true;
}

void ACodec::ExecutingState::stateEntered() {
    ALOGD("[%s] Now Executing", mCodec->mComponentName.c_str());

    mCodec->processDeferredMessages();
}

bool ACodec::ExecutingState::onMessageReceived(const sp<AMessage> &msg) {
    bool handled = false;

    switch (msg->what()) {
        case kWhatShutdown:
        {
            int32_t keepComponentAllocated;
            CHECK(msg->findInt32(
                        "keepComponentAllocated", &keepComponentAllocated));

            mCodec->mShutdownInProgress = true;
            mCodec->mKeepComponentAllocated = keepComponentAllocated;

            mActive = false;

            CHECK_EQ(mCodec->mOMX->sendCommand(
                        mCodec->mNode, OMX_CommandStateSet, OMX_StateIdle),
                     (status_t)OK);

            mCodec->changeState(mCodec->mExecutingToIdleState);

            handled = true;
            break;
        }

        case kWhatFlush:
        {
            ALOGD("[%s] ExecutingState flushing now "
                 "(codec owns %d/%d input, %d/%d output).",
                    mCodec->mComponentName.c_str(),
                    mCodec->countBuffersOwnedByComponent(kPortIndexInput),
                    mCodec->mBuffers[kPortIndexInput].size(),
                    mCodec->countBuffersOwnedByComponent(kPortIndexOutput),
                    mCodec->mBuffers[kPortIndexOutput].size());

            mActive = false;

#ifndef ANDROID_DEFAULT_CODE
            if (mCodec->mLeftOverBuffer != NULL) {
                ALOGI("clear mLeftOverBuffer %x", mCodec->mLeftOverBuffer.get());
                mCodec->mLeftOverBuffer = NULL;
            }
#endif
            CHECK_EQ(mCodec->mOMX->sendCommand(
                        mCodec->mNode, OMX_CommandFlush, OMX_ALL),
                     (status_t)OK);

            mCodec->changeState(mCodec->mFlushingState);

            handled = true;
            break;
        }

        case kWhatResume:
        {
            resume();

            handled = true;
            break;
        }


        case kWhatRequestIDRFrame:
        {
            status_t err = mCodec->requestIDRFrame();
            if (err != OK) {
                ALOGW("Requesting an IDR frame failed.");
            }

            handled = true;
            break;
        }
#ifndef ANDROID_DEFAULT_CODE
        case kWhatMtkVEncIFrameInterval:
        {
            int seconds;
            msg->findInt32("MtkVEncIRate", &seconds);
            status_t err = mCodec->setVEncIInterval(seconds);
            break;
        }
#endif//ANDROID_DEFAULT_CODE

        default:
            handled = BaseState::onMessageReceived(msg);
            break;
    }

    return handled;
}

bool ACodec::ExecutingState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    switch (event) {
        case OMX_EventPortSettingsChanged:
        {
            CHECK_EQ(data1, (OMX_U32)kPortIndexOutput);

            if (data2 == 0 || data2 == OMX_IndexParamPortDefinition) {
                CHECK_EQ(mCodec->mOMX->sendCommand(
                            mCodec->mNode,
                            OMX_CommandPortDisable, kPortIndexOutput),
                         (status_t)OK);

                mCodec->freeOutputBuffersNotOwnedByComponent();

                mCodec->changeState(mCodec->mOutputPortSettingsChangedState);
            } else if (data2 == OMX_IndexConfigCommonOutputCrop) {
                mCodec->mSentFormat = false;
            } else {
                ALOGV("[%s] OMX_EventPortSettingsChanged 0x%08lx",
                     mCodec->mComponentName.c_str(), data2);
            }

            return true;
        }

        case OMX_EventBufferFlag:
        {
            return true;
        }

        default:
            return BaseState::onOMXEvent(event, data1, data2);
    }
}

////////////////////////////////////////////////////////////////////////////////

ACodec::OutputPortSettingsChangedState::OutputPortSettingsChangedState(
        ACodec *codec)
    : BaseState(codec) {
}

ACodec::BaseState::PortMode ACodec::OutputPortSettingsChangedState::getPortMode(
        OMX_U32 portIndex) {
    if (portIndex == kPortIndexOutput) {
        return FREE_BUFFERS;
    }

    CHECK_EQ(portIndex, (OMX_U32)kPortIndexInput);

    return RESUBMIT_BUFFERS;
}

bool ACodec::OutputPortSettingsChangedState::onMessageReceived(
        const sp<AMessage> &msg) {
    bool handled = false;

    switch (msg->what()) {
        case kWhatFlush:
        case kWhatShutdown:
        case kWhatResume:
        {
            if (msg->what() == kWhatResume) {
                ALOGV("[%s] Deferring resume", mCodec->mComponentName.c_str());
            }

            mCodec->deferMessage(msg);
            handled = true;
            break;
        }

        default:
            handled = BaseState::onMessageReceived(msg);
            break;
    }

    return handled;
}

void ACodec::OutputPortSettingsChangedState::stateEntered() {
    ALOGD("[%s] Now handling output port settings change",
         mCodec->mComponentName.c_str());
}

bool ACodec::OutputPortSettingsChangedState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    switch (event) {
        case OMX_EventCmdComplete:
        {
            if (data1 == (OMX_U32)OMX_CommandPortDisable) {
                CHECK_EQ(data2, (OMX_U32)kPortIndexOutput);

                ALOGV("[%s] Output port now disabled.",
                        mCodec->mComponentName.c_str());

                CHECK(mCodec->mBuffers[kPortIndexOutput].isEmpty());
                mCodec->mDealer[kPortIndexOutput].clear();

                CHECK_EQ(mCodec->mOMX->sendCommand(
                            mCodec->mNode, OMX_CommandPortEnable, kPortIndexOutput),
                         (status_t)OK);

                status_t err;
                if ((err = mCodec->allocateBuffersOnPort(
                                kPortIndexOutput)) != OK) {
                    ALOGE("Failed to allocate output port buffers after "
                         "port reconfiguration (error 0x%08x)",
                         err);

                    mCodec->signalError(OMX_ErrorUndefined, err);

                    // This is technically not correct, but appears to be
                    // the only way to free the component instance.
                    // Controlled transitioning from excecuting->idle
                    // and idle->loaded seem impossible probably because
                    // the output port never finishes re-enabling.
                    mCodec->mShutdownInProgress = true;
                    mCodec->mKeepComponentAllocated = false;
                    mCodec->changeState(mCodec->mLoadedState);
                }

                return true;
            } else if (data1 == (OMX_U32)OMX_CommandPortEnable) {
                CHECK_EQ(data2, (OMX_U32)kPortIndexOutput);

                mCodec->mSentFormat = false;

                ALOGV("[%s] Output port now reenabled.",
                        mCodec->mComponentName.c_str());

                if (mCodec->mExecutingState->active()) {
                    mCodec->mExecutingState->submitOutputBuffers();
                }

                mCodec->changeState(mCodec->mExecutingState);

                return true;
            }

            return false;
        }

        default:
            return false;
    }
}

////////////////////////////////////////////////////////////////////////////////

ACodec::ExecutingToIdleState::ExecutingToIdleState(ACodec *codec)
    : BaseState(codec),
      mComponentNowIdle(false) {
}

bool ACodec::ExecutingToIdleState::onMessageReceived(const sp<AMessage> &msg) {
    bool handled = false;

    switch (msg->what()) {
        case kWhatFlush:
        {
            // Don't send me a flush request if you previously wanted me
            // to shutdown.
            TRESPASS();
            break;
        }

        case kWhatShutdown:
        {
            // We're already doing that...

            handled = true;
            break;
        }

        default:
            handled = BaseState::onMessageReceived(msg);
            break;
    }

    return handled;
}

void ACodec::ExecutingToIdleState::stateEntered() {
    ALOGD("[%s] Now Executing->Idle", mCodec->mComponentName.c_str());

    mComponentNowIdle = false;
    mCodec->mSentFormat = false;
}

bool ACodec::ExecutingToIdleState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    switch (event) {
        case OMX_EventCmdComplete:
        {
            CHECK_EQ(data1, (OMX_U32)OMX_CommandStateSet);
            CHECK_EQ(data2, (OMX_U32)OMX_StateIdle);

            mComponentNowIdle = true;

            changeStateIfWeOwnAllBuffers();

            return true;
        }

        case OMX_EventPortSettingsChanged:
        case OMX_EventBufferFlag:
        {
            // We're shutting down and don't care about this anymore.
            return true;
        }

        default:
            return BaseState::onOMXEvent(event, data1, data2);
    }
}

void ACodec::ExecutingToIdleState::changeStateIfWeOwnAllBuffers() {
    if (mComponentNowIdle && mCodec->allYourBuffersAreBelongToUs()) {
        CHECK_EQ(mCodec->mOMX->sendCommand(
                    mCodec->mNode, OMX_CommandStateSet, OMX_StateLoaded),
                 (status_t)OK);

        CHECK_EQ(mCodec->freeBuffersOnPort(kPortIndexInput), (status_t)OK);
        CHECK_EQ(mCodec->freeBuffersOnPort(kPortIndexOutput), (status_t)OK);

        if (mCodec->mFlags & kFlagIsSecure && mCodec->mNativeWindow != NULL) {
            // We push enough 1x1 blank buffers to ensure that one of
            // them has made it to the display.  This allows the OMX
            // component teardown to zero out any protected buffers
            // without the risk of scanning out one of those buffers.
            mCodec->pushBlankBuffersToNativeWindow();
        }

        mCodec->changeState(mCodec->mIdleToLoadedState);
    }
}

void ACodec::ExecutingToIdleState::onInputBufferFilled(
        const sp<AMessage> &msg) {
    BaseState::onInputBufferFilled(msg);

    changeStateIfWeOwnAllBuffers();
}

void ACodec::ExecutingToIdleState::onOutputBufferDrained(
        const sp<AMessage> &msg) {
    BaseState::onOutputBufferDrained(msg);

    changeStateIfWeOwnAllBuffers();
}

////////////////////////////////////////////////////////////////////////////////

ACodec::IdleToLoadedState::IdleToLoadedState(ACodec *codec)
    : BaseState(codec) {
}

bool ACodec::IdleToLoadedState::onMessageReceived(const sp<AMessage> &msg) {
    bool handled = false;

    switch (msg->what()) {
        case kWhatShutdown:
        {
            // We're already doing that...

            handled = true;
            break;
        }

        case kWhatFlush:
        {
            // Don't send me a flush request if you previously wanted me
            // to shutdown.
            TRESPASS();
            break;
        }

        default:
            handled = BaseState::onMessageReceived(msg);
            break;
    }

    return handled;
}

void ACodec::IdleToLoadedState::stateEntered() {
    ALOGD("[%s] Now Idle->Loaded", mCodec->mComponentName.c_str());
}

bool ACodec::IdleToLoadedState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    switch (event) {
        case OMX_EventCmdComplete:
        {
            CHECK_EQ(data1, (OMX_U32)OMX_CommandStateSet);
            CHECK_EQ(data2, (OMX_U32)OMX_StateLoaded);

            mCodec->changeState(mCodec->mLoadedState);

            return true;
        }

        default:
            return BaseState::onOMXEvent(event, data1, data2);
    }
}

////////////////////////////////////////////////////////////////////////////////

ACodec::FlushingState::FlushingState(ACodec *codec)
    : BaseState(codec) {
}

void ACodec::FlushingState::stateEntered() {
    ALOGD("[%s] Now Flushing", mCodec->mComponentName.c_str());

    mFlushComplete[kPortIndexInput] = mFlushComplete[kPortIndexOutput] = false;
}

bool ACodec::FlushingState::onMessageReceived(const sp<AMessage> &msg) {
    bool handled = false;

    switch (msg->what()) {
        case kWhatShutdown:
        {
            mCodec->deferMessage(msg);
            break;
        }

        case kWhatFlush:
        {
            // We're already doing this right now.
            handled = true;
            break;
        }

        default:
            handled = BaseState::onMessageReceived(msg);
            break;
    }

    return handled;
}

bool ACodec::FlushingState::onOMXEvent(
        OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2) {
    ALOGV("[%s] FlushingState onOMXEvent(%d,%ld)",
            mCodec->mComponentName.c_str(), event, data1);

    switch (event) {
        case OMX_EventCmdComplete:
        {
            CHECK_EQ(data1, (OMX_U32)OMX_CommandFlush);

            if (data2 == kPortIndexInput || data2 == kPortIndexOutput) {
                CHECK(!mFlushComplete[data2]);
                mFlushComplete[data2] = true;

                if (mFlushComplete[kPortIndexInput]
                        && mFlushComplete[kPortIndexOutput]) {
                    changeStateIfWeOwnAllBuffers();
                }
            } else {
                CHECK_EQ(data2, OMX_ALL);
                CHECK(mFlushComplete[kPortIndexInput]);
                CHECK(mFlushComplete[kPortIndexOutput]);

                changeStateIfWeOwnAllBuffers();
            }

            return true;
        }

        case OMX_EventPortSettingsChanged:
        {
            sp<AMessage> msg = new AMessage(kWhatOMXMessage, mCodec->id());
            msg->setInt32("type", omx_message::EVENT);
            msg->setPointer("node", mCodec->mNode);
            msg->setInt32("event", event);
            msg->setInt32("data1", data1);
            msg->setInt32("data2", data2);

            ALOGV("[%s] Deferring OMX_EventPortSettingsChanged",
                 mCodec->mComponentName.c_str());

            mCodec->deferMessage(msg);

            return true;
        }

        default:
            return BaseState::onOMXEvent(event, data1, data2);
    }

    return true;
}

void ACodec::FlushingState::onOutputBufferDrained(const sp<AMessage> &msg) {
    BaseState::onOutputBufferDrained(msg);

    changeStateIfWeOwnAllBuffers();
}

void ACodec::FlushingState::onInputBufferFilled(const sp<AMessage> &msg) {
    BaseState::onInputBufferFilled(msg);

    changeStateIfWeOwnAllBuffers();
}

void ACodec::FlushingState::changeStateIfWeOwnAllBuffers() {
    if (mFlushComplete[kPortIndexInput]
            && mFlushComplete[kPortIndexOutput]
            && mCodec->allYourBuffersAreBelongToUs()) {
        sp<AMessage> notify = mCodec->mNotify->dup();
        notify->setInt32("what", ACodec::kWhatFlushCompleted);
        notify->post();

        mCodec->mPortEOS[kPortIndexInput] =
            mCodec->mPortEOS[kPortIndexOutput] = false;

        mCodec->mInputEOSResult = OK;

        mCodec->changeState(mCodec->mExecutingState);
    }
}

}  // namespace android
