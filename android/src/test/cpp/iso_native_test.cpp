#define SCOPE_ISO_TEST
#include "../../main/cpp/scope_iso.cpp"
#include <cassert>
#include <atomic>
#include <deque>
#include <set>
#include <thread>
#include <iostream>

using namespace scope_iso;

struct FakeIo : Io {
    std::set<usbdevfs_urb*> pending;
    std::deque<usbdevfs_urb*> completed;
    int submissions = 0;
    int failAtSubmit = -1;
    int discards = 0;
    int closes = 0;
    bool stuck = false;
    bool interrupted = false;
    bool foreign = false;
    std::atomic<bool> enteredWait{false};
    int duplicate(int) override { return 100; }
    void closeFd(int) override { ++closes; }
    int submit(int, usbdevfs_urb* urb) override {
        if (++submissions == failAtSubmit) { errno = ENOSPC; return -1; }
        assert(!pending.count(urb));
        assert(urb->type == USBDEVFS_URB_TYPE_ISO);
        assert(urb->endpoint == 0x81);
        assert(urb->flags == USBDEVFS_URB_ISO_ASAP);
        assert(urb->number_of_packets == kPackets);
        pending.insert(urb);
        return 0;
    }
    void complete(usbdevfs_urb* urb) {
        assert(pending.erase(urb) == 1);
        completed.push_back(urb);
    }
    void completeAll() {
        while (!pending.empty()) complete(*pending.begin());
    }
    int discard(int, usbdevfs_urb* urb) override {
        ++discards;
        if (stuck) { errno = EIO; return -1; }
        if (!pending.count(urb)) { errno = EINVAL; return -1; }
        urb->status = -ECONNRESET;
        complete(urb);
        return 0;
    }
    int reap(int, void** urb) override {
        if (interrupted) { errno = EINTR; return -1; }
        if (foreign) { foreign = false; *urb = reinterpret_cast<void*>(0x1234); return 0; }
        if (completed.empty()) { errno = EAGAIN; return -1; }
        *urb = completed.front(); completed.pop_front(); return 0;
    }
    int wait(int, int timeout) override {
        enteredWait = true;
        std::this_thread::sleep_for(std::chrono::milliseconds(timeout > 1 ? 1 : timeout));
        return 0;
    }
};

template<class F> bool throws(F&& f) {
    try { f(); return false; } catch (const std::exception&) { return true; }
}

void packetBatchAndLifecycle() {
    auto io = std::make_shared<FakeIo>();
    const auto handle = scope_iso::open(7, 0x81, 8, io);
    assert(io->pending.size() == 8);
    auto* urb = *io->pending.begin();
    auto* bytes = static_cast<unsigned char*>(urb->buffer);
    bytes[0] = 2; bytes[1] = 0; bytes[2] = 42;
    bytes[8] = 2; bytes[9] = 2; bytes[10] = 43;
    urb->iso_frame_desc[0].actual_length = 3;
    urb->iso_frame_desc[1].actual_length = 3;
    urb->iso_frame_desc[2].status = static_cast<unsigned int>(-EPROTO);
    io->complete(urb);
    const auto batch = lookup(handle)->read(20);
    assert(batch.count == 32 && batch.bytes.size() == 256);
    assert(batch.bytes[2] == 42 && batch.bytes[10] == 43);
    assert(batch.metadata[0] == 3 && batch.metadata[2] == 3);
    assert(batch.metadata[5] == -EPROTO);
    assert(io->submissions == 9 && io->pending.size() == 8);
    // Changing the requeued USB allocation cannot change the already returned frame packet data.
    bytes[2] = 99;
    assert(batch.bytes[2] == 42);
    scope_iso::close(handle);
    assert(io->discards == 8 && io->pending.empty() && io->completed.empty());
    assert(io->closes == 1);
    scope_iso::close(handle);
    assert(io->closes == 1 && throws([&] { lookup(handle); }));
}

void malformedAndPartialSubmission() {
    auto io = std::make_shared<FakeIo>();
    io->failAtSubmit = 4;
    assert(throws([&] { scope_iso::open(7, 0x81, 8, io); }));
    assert(io->pending.empty() && io->completed.empty() && io->closes == 1);
    io = std::make_shared<FakeIo>();
    auto handle = scope_iso::open(7, 0x81, 8, io);
    auto* urb = *io->pending.begin();
    urb->iso_frame_desc[0].actual_length = 9;
    io->complete(urb);
    assert(throws([&] { lookup(handle)->read(20); }));
    scope_iso::close(handle);
    assert(io->closes == 1);
    io = std::make_shared<FakeIo>();
    handle = scope_iso::open(7, 0x81, 8, io);
    io->foreign = true;
    assert(throws([&] { lookup(handle)->read(20); }));
    scope_iso::close(handle);
    assert(io->closes == 1);
    assert(throws([&] { scope_iso::open(-1, 0x81, 8, io); }));
    assert(throws([&] { scope_iso::open(7, 0x01, 8, io); }));
    assert(throws([&] { scope_iso::open(7, 0x81, 32769, io); }));
}

void timeoutsAndConcurrentClose() {
    auto io = std::make_shared<FakeIo>();
    const auto handle = scope_iso::open(7, 0x81, 8, io);
    io->interrupted = true;
    const auto started = Clock::now();
    assert(lookup(handle)->read(15).count == 0);
    assert(Clock::now() - started < std::chrono::milliseconds(500));
    io->interrupted = false;
    auto session = lookup(handle);
    std::thread reading([&] { assert(session->read(30).count == 0); });
    while (!io->enteredWait) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    scope_iso::close(handle);
    reading.join();
    assert(io->closes == 0); // a concurrent JNI caller still owns its shared session reference
    session.reset();
    assert(io->closes == 1);
}

void quarantineIsBoundedAndDrained() {
    std::vector<std::shared_ptr<FakeIo>> kernels;
    for (size_t i = 0; i < kMaxSessions; ++i) {
        auto io = std::make_shared<FakeIo>();
        const auto handle = scope_iso::open(7, 0x81, 8, io);
        io->stuck = true;
        assert(throws([&] { scope_iso::close(handle); }));
        assert(io->closes == 0 && io->pending.size() == 8);
        kernels.push_back(io);
    }
    assert(throws([&] { scope_iso::open(7, 0x81, 8); }));
    // A later completion finally releases each retained write destination and duplicate fd.
    for (auto& io : kernels) io->completeAll();
    auto recovered = std::make_shared<FakeIo>();
    const auto handle = scope_iso::open(7, 0x81, 8, recovered);
    for (const auto& io : kernels) assert(io->closes == 1);
    scope_iso::close(handle);
    assert(registry().active.empty() && registry().quarantine.empty());
}

int main() {
    packetBatchAndLifecycle();
    malformedAndPartialSubmission();
    timeoutsAndConcurrentClose();
    quarantineIsBoundedAndDrained();
    delete &registry(); // test-only: all sessions are drained, so leak detection can remain enabled.
    std::cout << "Native ISO tests passed: packets, lifecycle, malformed input, startup failure, timeouts, concurrent close, bounded quarantine\n";
}
