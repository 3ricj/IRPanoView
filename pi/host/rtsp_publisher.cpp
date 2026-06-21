#include "rtsp_publisher.h"

#include "thermal_renderer.h"

#include <gst/app/gstappsrc.h>
#include <gst/gst.h>
#include <gst/rtsp-server/rtsp-server.h>
#include <glib.h>

#include <chrono>
#include <cstring>
#include <sstream>
#include <vector>

namespace irpv {

namespace {

struct PushJob {
    RtspPublisher* self = nullptr;
    std::vector<uint8_t> bytes;
};

gboolean pushIdle(gpointer user_data) {
    auto* job = static_cast<PushJob*>(user_data);
    if (job && job->self) {
        job->self->pushNv12FrameOnContext(job->bytes.data(), job->bytes.size());
    }
    delete job;
    return G_SOURCE_REMOVE;
}

} // namespace

RtspPublisher::RtspPublisher() = default;

RtspPublisher::~RtspPublisher() {
    stop();
}

void RtspPublisher::storeAppSrc(_GstElement* appsrc) {
    std::lock_guard<std::mutex> lock(appsrc_mutex_);
    if (appsrc_) {
        gst_object_unref(appsrc_);
    }
    appsrc_ = appsrc;
    if (appsrc_) {
        gst_object_ref(appsrc_);
    }
}

void RtspPublisher::clearAppSrc() {
    std::lock_guard<std::mutex> lock(appsrc_mutex_);
    if (appsrc_) {
        gst_object_unref(appsrc_);
        appsrc_ = nullptr;
    }
    frame_num_ = 0;
}

void RtspPublisher::onMediaUnprepared(void* media, void* user_data) {
    (void)media;
    static_cast<RtspPublisher*>(user_data)->clearAppSrc();
}

void RtspPublisher::onMediaConfigure(void* factory, void* media, void* user_data) {
    (void)factory;
    auto* self = static_cast<RtspPublisher*>(user_data);
    auto* gst_media = static_cast<GstRTSPMedia*>(media);
    g_signal_connect(gst_media, "unprepared", G_CALLBACK(onMediaUnprepared), self);
    GstElement* element = gst_rtsp_media_get_element(gst_media);
    if (!element) {
        return;
    }
    GstElement* src = gst_bin_get_by_name(GST_BIN(element), "mysrc");
    if (src) {
        self->storeAppSrc(src);
        gst_object_unref(src);
    }
    gst_object_unref(element);
}

void RtspPublisher::serverThreadMain() {
    gst_init(nullptr, nullptr);

    auto* server = gst_rtsp_server_new();
    g_object_set(server, "service", std::to_string(port_).c_str(), nullptr);

    auto* factory = gst_rtsp_media_factory_new();
    std::ostringstream launch;
    launch << "( appsrc name=mysrc is-live=true format=time do-timestamp=false max-buffers=2 leaky-type=downstream "
           << "caps=video/x-raw,format=NV12,width=" << width_
           << ",height=" << height_ << ",framerate=25/1 "
           << "! v4l2h264enc extra-controls=\"controls,video_bitrate=" << bitrate_
           << ",h264_i_frame_period=25,h264_profile=4\" "
           << "! video/x-h264,level=(string)4 "
           << "! h264parse config-interval=1 "
           << "! rtph264pay name=pay0 pt=96 config-interval=1 mtu=1400 )";
    gst_rtsp_media_factory_set_launch(factory, launch.str().c_str());
    gst_rtsp_media_factory_set_shared(factory, TRUE);
    gst_rtsp_media_factory_set_suspend_mode(factory, GST_RTSP_SUSPEND_MODE_NONE);
    gst_rtsp_media_factory_set_protocols(factory, static_cast<GstRTSPLowerTrans>(GST_RTSP_LOWER_TRANS_TCP));
    gst_rtsp_media_factory_set_latency(factory, 0);
    g_signal_connect(factory, "media-configure", G_CALLBACK(onMediaConfigure), this);

    auto* mounts = gst_rtsp_server_get_mount_points(server);
    gst_rtsp_mount_points_add_factory(mounts, mount_.c_str(), factory);
    g_object_unref(mounts);

    server_ = server;
    loop_ = g_main_loop_new(nullptr, FALSE);
    const guint id = gst_rtsp_server_attach(server, nullptr);
    if (id == 0) {
        running_ = false;
        return;
    }
    g_main_loop_run(loop_);
}

bool RtspPublisher::start(uint16_t port, const std::string& mount_path, int bitrate, int width, int height) {
    stop();
    port_ = port;
    mount_ = mount_path.empty() ? "/thermal" : mount_path;
    bitrate_ = bitrate;
    width_ = width > 0 ? width : kPanoWidth;
    height_ = height > 0 ? height : kPanoHeight;
    running_ = true;
    server_thread_ = std::thread(&RtspPublisher::serverThreadMain, this);
    for (int i = 0; i < 50 && appsrc_ == nullptr && running_; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    return running_;
}

void RtspPublisher::stop() {
    running_ = false;
    if (loop_) {
        g_main_loop_quit(loop_);
    }
    if (server_thread_.joinable()) {
        server_thread_.join();
    }
    if (loop_) {
        g_main_loop_unref(loop_);
        loop_ = nullptr;
    }
    if (server_) {
        g_object_unref(server_);
        server_ = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(appsrc_mutex_);
        if (appsrc_) {
            gst_object_unref(appsrc_);
            appsrc_ = nullptr;
        }
    }
    queue_depth_ = 0;
}

bool RtspPublisher::hasClient() const {
    std::lock_guard<std::mutex> lock(appsrc_mutex_);
    return appsrc_ != nullptr;
}

bool RtspPublisher::pushNv12FrameOnContext(const uint8_t* nv12, size_t bytes) {
    std::lock_guard<std::mutex> lock(appsrc_mutex_);
    if (!appsrc_ || !nv12 || bytes == 0) {
        return false;
    }

    GstBuffer* buffer = gst_buffer_new_allocate(nullptr, bytes, nullptr);
    if (!buffer) {
        return false;
    }
    GstMapInfo map{};
    if (!gst_buffer_map(buffer, &map, GST_MAP_WRITE)) {
        gst_buffer_unref(buffer);
        return false;
    }
    std::memcpy(map.data, nv12, bytes);
    gst_buffer_unmap(buffer, &map);

    const uint64_t frame = frame_num_++;
    GST_BUFFER_PTS(buffer) = frame * (GST_SECOND / 25);
    GST_BUFFER_DTS(buffer) = GST_BUFFER_PTS(buffer);
    GST_BUFFER_DURATION(buffer) = GST_SECOND / 25;
    GST_BUFFER_FLAG_SET(buffer, GST_BUFFER_FLAG_LIVE);

    const GstFlowReturn flow = gst_app_src_push_buffer(GST_APP_SRC(appsrc_), buffer);
    queue_depth_ = static_cast<int>(gst_app_src_get_current_level_bytes(GST_APP_SRC(appsrc_)));
    return flow == GST_FLOW_OK;
}

bool RtspPublisher::pushNv12Frame(const uint8_t* nv12, size_t bytes, uint64_t pts_us) {
    (void)pts_us;
    if (!loop_ || !hasClient() || !nv12 || bytes == 0) {
        return false;
    }
    auto* job = new PushJob{this, std::vector<uint8_t>(nv12, nv12 + bytes)};
    GMainContext* ctx = g_main_loop_get_context(loop_);
    if (ctx == nullptr) {
        delete job;
        return false;
    }
    g_main_context_invoke(ctx, pushIdle, job);
    return true;
}

} // namespace irpv
