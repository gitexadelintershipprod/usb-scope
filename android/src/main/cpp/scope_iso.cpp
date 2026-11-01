// Android-authorized usbfs isochronous input. No paths, device enumeration or permission bypass.
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#ifndef SCOPE_ISO_TEST
#include <jni.h>
#endif

namespace scope_iso {
constexpr int kPackets = 32;
constexpr int kQueueDepth = 8;
constexpr int kMaxPacketSize = 32768;
constexpr size_t kMaxSessions = 4;
using Clock = std::chrono::steady_clock;

struct Io {
    virtual ~Io() = default;
    virtual int duplicate(int fd) { return fcntl(fd, F_DUPFD_CLOEXEC, 0); }
    virtual void closeFd(int fd) { ::close(fd); }
    virtual int submit(int fd, usbdevfs_urb* urb) { return ioctl(fd, USBDEVFS_SUBMITURB, urb); }
    virtual int discard(int fd, usbdevfs_urb* urb) { return ioctl(fd, USBDEVFS_DISCARDURB, urb); }
    virtual int reap(int fd, void** urb) { return ioctl(fd, USBDEVFS_REAPURBNDELAY, urb); }
    virtual int wait(int fd, int timeout) {
        pollfd item{fd, POLLOUT, 0};
        return poll(&item, 1, timeout);
    }
};

std::runtime_error failure(const char* operation, int error) {
    return std::runtime_error(std::string(operation) + ": " + std::strerror(error) +
        " (errno " + std::to_string(error) + ")");
}

struct Slot {
    usbdevfs_urb* urb = nullptr;
    std::unique_ptr<unsigned char[]> bytes;
    bool pending = false;
    explicit Slot(int packetSize) : bytes(new unsigned char[packetSize * kPackets]()) {
        urb = static_cast<usbdevfs_urb*>(std::calloc(1,
            sizeof(usbdevfs_urb) + kPackets * sizeof(usbdevfs_iso_packet_desc)));
        if (!urb) throw std::bad_alloc();
    }
    ~Slot() { std::free(urb); }
    void leakForKernelSafety() { urb = nullptr; (void)bytes.release(); }
};

struct Batch {
    int count = 0;
    std::vector<unsigned char> bytes;
    std::array<int, kPackets * 2> metadata{};
};

class Session {
    std::shared_ptr<Io> io_;
    int fd_;
    int endpoint_;
    int packetSize_;
    std::array<std::unique_ptr<Slot>, kQueueDepth> slots_{};
    std::mutex mutex_;
    bool closed_ = false;

    Slot* find(void* address) {
        for (auto& slot : slots_) if (slot && slot->urb == address) return slot.get();
        return nullptr;
    }

    void submit(Slot& slot) {
        auto* urb = slot.urb;
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = static_cast<unsigned char>(endpoint_);
        urb->status = 0;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = slot.bytes.get();
        urb->buffer_length = packetSize_ * kPackets;
        urb->actual_length = 0;
        urb->start_frame = 0;
        urb->number_of_packets = kPackets;
        urb->error_count = 0;
        urb->signr = 0;
        urb->usercontext = nullptr;
        for (int i = 0; i < kPackets; ++i) {
            urb->iso_frame_desc[i].length = static_cast<unsigned int>(packetSize_);
            urb->iso_frame_desc[i].actual_length = 0;
            urb->iso_frame_desc[i].status = 0;
        }
        int result;
        int interrupts = 0;
        do { result = io_->submit(fd_, urb); } while (result < 0 && errno == EINTR && ++interrupts < 8);
        if (result < 0) throw failure("USBDEVFS_SUBMITURB", errno);
        slot.pending = true;
    }

    size_t pendingCount() const {
        size_t n = 0;
        for (const auto& slot : slots_) if (slot && slot->pending) ++n;
        return n;
    }

    // REAP writes both packet data and completion fields into the original userspace allocation.
    // DISCARD alone is therefore insufficient to release either the URB or its data buffer.
    bool drainUnlocked(int milliseconds) noexcept {
        const auto deadline = Clock::now() + std::chrono::milliseconds(milliseconds);
        while (pendingCount() != 0) {
            void* completed = nullptr;
            const int result = io_->reap(fd_, &completed);
            if (result == 0) {
                Slot* slot = find(completed);
                // Never dereference a pointer not allocated by this session. Another reaper is
                // a programming error; preserving our allocations is safer than guessing.
                if (!slot || !slot->pending) return false;
                slot->pending = false;
                continue;
            }
            if (errno == EINTR) {
                if (Clock::now() >= deadline) return false;
                continue;
            }
            if (errno != EAGAIN && errno != ENODEV && errno != ENOENT) return false;
            if (Clock::now() >= deadline) return false;
            // On unplug, queued completions can still be reaped. Do not treat ENODEV itself as
            // proof that the userspace write destinations are no longer referenced by usbfs.
            (void)io_->wait(fd_, 2);
        }
        return true;
    }

public:
    Session(int fd, int endpoint, int packetSize, std::shared_ptr<Io> io)
        : io_(std::move(io)), fd_(-1), endpoint_(endpoint), packetSize_(packetSize) {
        if (fd < 0 || endpoint < 0x81 || endpoint > 0x8f || packetSize < 1 || packetSize > kMaxPacketSize)
            throw std::invalid_argument("Invalid isochronous USB descriptor or packet capacity");
        fd_ = io_->duplicate(fd);
        if (fd_ < 0) throw failure("duplicate Android USB descriptor", errno);
        try {
            for (auto& slot : slots_) slot = std::make_unique<Slot>(packetSize_);
        } catch (...) {
            io_->closeFd(fd_); fd_ = -1; throw;
        }
    }
    ~Session() {
        // Defense in depth: even an exceptional ownership bug must never free memory to which
        // the kernel may still write. Normal/quarantined ownership is handled by Registry below.
        if (pendingCount() != 0) {
            for (auto& slot : slots_) if (slot) slot->leakForKernelSafety();
            fd_ = -1;
        }
        if (fd_ >= 0) io_->closeFd(fd_);
    }
    void start() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& slot : slots_) submit(*slot);
    }
    Batch read(int timeoutMs) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_) throw std::runtime_error("Isochronous USB stream is closed");
        if (timeoutMs <= 0 || timeoutMs > 1000) throw std::invalid_argument("Invalid isochronous read timeout");
        const auto deadline = Clock::now() + std::chrono::milliseconds(timeoutMs);
        void* completed = nullptr;
        while (true) {
            const int result = io_->reap(fd_, &completed);
            if (result == 0) break;
            const int error = errno;
            if (error == EINTR) {
                if (Clock::now() >= deadline) return {};
                continue;
            }
            if (error != EAGAIN) throw failure("USBDEVFS_REAPURBNDELAY", error);
            const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count();
            if (remaining <= 0) return {};
            const int waited = io_->wait(fd_, static_cast<int>(remaining > 10 ? 10 : remaining));
            if (waited < 0 && errno != EINTR) throw failure("wait for isochronous USB", errno);
        }
        Slot* slot = find(completed);
        if (!slot || !slot->pending) throw std::runtime_error("Unexpected USB completion; exclusive reaper required");
        slot->pending = false;
        auto* urb = slot->urb;
        if (urb->number_of_packets != kPackets) throw std::runtime_error("Invalid USB completion packet count");
        Batch batch;
        batch.count = kPackets;
        batch.bytes.resize(static_cast<size_t>(packetSize_) * kPackets, 0);
        for (int i = 0; i < kPackets; ++i) {
            const auto& packet = urb->iso_frame_desc[i];
            const unsigned int length = packet.actual_length;
            int status = static_cast<int>(packet.status);
            if (urb->status != 0 && status == 0) status = urb->status;
            if (length > static_cast<unsigned int>(packetSize_) || packet.length != static_cast<unsigned int>(packetSize_))
                throw std::runtime_error("Invalid USB completion packet length");
            batch.metadata[i * 2] = status == 0 ? static_cast<int>(length) : 0;
            batch.metadata[i * 2 + 1] = status;
            if (status == 0 && length != 0) std::memcpy(
                batch.bytes.data() + i * packetSize_, slot->bytes.get() + i * packetSize_, length);
        }
        // Requeue only after copying every packet to independently owned memory. The USB host
        // may overwrite this URB immediately; Java receives the detached Batch below.
        submit(*slot);
        return batch;
    }
    bool stop() noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        closed_ = true;
        for (auto& slot : slots_) {
            if (!slot || !slot->pending) continue;
            int result;
            int interrupts = 0;
            do { result = io_->discard(fd_, slot->urb); } while (result < 0 && errno == EINTR && ++interrupts < 8);
            // EINVAL/ENODEV can mean completion already happened; still drain that completion.
        }
        return drainUnlocked(1000);
    }
    bool retryDrain() noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        return drainUnlocked(0);
    }
};

struct Registry {
    std::mutex mutex;
    std::unordered_map<int64_t, std::shared_ptr<Session>> active;
    std::vector<std::shared_ptr<Session>> quarantine;
    size_t constructing = 0;
    int64_t next = 1;
    Registry() { quarantine.reserve(kMaxSessions); active.reserve(kMaxSessions); }
};

Registry& registry() {
    // Deliberately process-lifetime ownership. Quarantined URBs remain mapped until the OS closes
    // the process's descriptors; static destruction must not free their kernel write destinations.
    static Registry* value = new Registry();
    return *value;
}

int64_t open(int fd, int endpoint, int packetSize, std::shared_ptr<Io> io = std::make_shared<Io>()) {
    auto& r = registry();
    {
        std::lock_guard<std::mutex> lock(r.mutex);
        for (auto it = r.quarantine.begin(); it != r.quarantine.end();) {
            if ((*it)->retryDrain()) it = r.quarantine.erase(it); else ++it;
        }
        if (r.active.size() + r.quarantine.size() + r.constructing >= kMaxSessions)
            throw std::runtime_error("USB cleanup is incomplete; reconnect the camera and restart the app");
        ++r.constructing;
    }
    std::shared_ptr<Session> session;
    try {
        session = std::make_shared<Session>(fd, endpoint, packetSize, std::move(io));
        session->start();
        std::lock_guard<std::mutex> lock(r.mutex);
        if (r.next == std::numeric_limits<int64_t>::max()) throw std::runtime_error("USB handle limit reached");
        const int64_t handle = r.next++;
        r.active.emplace(handle, session);
        --r.constructing;
        return handle;
    } catch (...) {
        const bool drained = !session || session->stop();
        std::lock_guard<std::mutex> lock(r.mutex);
        --r.constructing;
        if (!drained) r.quarantine.push_back(std::move(session));
        throw;
    }
}

std::shared_ptr<Session> lookup(int64_t handle) {
    auto& r = registry();
    std::lock_guard<std::mutex> lock(r.mutex);
    const auto found = r.active.find(handle);
    if (found == r.active.end()) throw std::runtime_error("Isochronous USB handle is closed or invalid");
    return found->second;
}

void close(int64_t handle) {
    auto& r = registry();
    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(r.mutex);
        const auto found = r.active.find(handle);
        if (found == r.active.end()) return;
        session = found->second;
    }
    const bool drained = session->stop();
    {
        std::lock_guard<std::mutex> lock(r.mutex);
        const auto found = r.active.find(handle);
        if (found != r.active.end() && found->second == session) {
            r.active.erase(found);
            if (!drained) r.quarantine.push_back(session);
        }
    }
    if (!drained) throw std::runtime_error("USB cancellation could not drain safely; native buffers retained");
}
} // namespace scope_iso

#ifndef SCOPE_ISO_TEST
namespace {
void throwIo(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/io/IOException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_technicallyvu_scope_usb_NativeIsochronous_nativeOpen(JNIEnv* env, jobject, jint fd, jint endpoint, jint size) {
    try { return scope_iso::open(fd, endpoint, size); }
    catch (const std::exception& e) { throwIo(env, e.what()); return 0; }
    catch (...) { throwIo(env, "Unknown native USB open failure"); return 0; }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_technicallyvu_scope_usb_NativeIsochronous_nativeRead(JNIEnv* env, jobject, jlong handle,
        jbyteArray payload, jintArray metadata, jint timeoutMs) {
    try {
        if (!payload || !metadata || env->GetArrayLength(metadata) < scope_iso::kPackets * 2)
            throw std::invalid_argument("Invalid native USB output arrays");
        auto session = scope_iso::lookup(handle);
        auto batch = session->read(timeoutMs);
        if (batch.count == 0) return 0;
        if (env->GetArrayLength(payload) < static_cast<jsize>(batch.bytes.size()))
            throw std::invalid_argument("Native USB payload array is too small");
        env->SetByteArrayRegion(payload, 0, static_cast<jsize>(batch.bytes.size()),
            reinterpret_cast<const jbyte*>(batch.bytes.data()));
        if (env->ExceptionCheck()) return 0;
        env->SetIntArrayRegion(metadata, 0, scope_iso::kPackets * 2,
            reinterpret_cast<const jint*>(batch.metadata.data()));
        return env->ExceptionCheck() ? 0 : batch.count;
    } catch (const std::exception& e) { throwIo(env, e.what()); return 0; }
    catch (...) { throwIo(env, "Unknown native USB read failure"); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_technicallyvu_scope_usb_NativeIsochronous_nativeClose(JNIEnv* env, jobject, jlong handle) {
    try { scope_iso::close(handle); }
    catch (const std::exception& e) { throwIo(env, e.what()); }
    catch (...) { throwIo(env, "Unknown native USB close failure"); }
}
#endif
