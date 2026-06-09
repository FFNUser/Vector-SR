#include <linux/memfd.h>
#include <sys/wait.h>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <string>
#include <vector>

#include "logging.h"

// Access to the process environment variables
extern "C" char **environ;

#if defined(__LP64__)
#define LP_SELECT(lp32, lp64) lp64
#else
#define LP_SELECT(lp32, lp64) lp32
#endif

namespace {

constexpr char kSockName[] = "5291374ceda0aef7c5d86cd2a4f6a3ac";
constexpr int kCmdIsNoInlineNeeded = 6;
constexpr int kCmdRecordDex2Oat = 7;

/**
 * Calculates a vector ID based on architecture and debug status.
 */
inline int get_id_vec(bool is64, bool is_debug) {
    return (static_cast<int>(is64) << 1) | static_cast<int>(is_debug);
}

/**
 * Wraps recvmsg with error logging.
 */
ssize_t xrecvmsg(int sockfd, struct msghdr *msg, int flags) {
    ssize_t rec = recvmsg(sockfd, msg, flags);
    if (rec < 0) {
        PLOGE("recvmsg");
    }
    return rec;
}

/**
 * Receives file descriptors passed over a Unix domain socket using SCM_RIGHTS.
 *
 * @return Pointer to the FD data on success, nullptr on failure.
 */
void *recv_fds(int sockfd, char *cmsgbuf, size_t bufsz, int cnt) {
    struct iovec iov = {
        .iov_base = &cnt,
        .iov_len = sizeof(cnt),
    };
    struct msghdr msg = {.msg_name = nullptr,
                         .msg_namelen = 0,
                         .msg_iov = &iov,
                         .msg_iovlen = 1,
                         .msg_control = cmsgbuf,
                         .msg_controllen = bufsz,
                         .msg_flags = 0};

    if (xrecvmsg(sockfd, &msg, MSG_WAITALL) < 0) return nullptr;

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);

    if (msg.msg_controllen != bufsz || cmsg == nullptr ||
        cmsg->cmsg_len != CMSG_LEN(sizeof(int) * cnt) || cmsg->cmsg_level != SOL_SOCKET ||
        cmsg->cmsg_type != SCM_RIGHTS) {
        return nullptr;
    }

    return CMSG_DATA(cmsg);
}

/**
 * Helper to receive a single FD from the socket.
 */
int recv_fd(int sockfd) {
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    void *data = recv_fds(sockfd, cmsgbuf, sizeof(cmsgbuf), 1);
    if (data == nullptr) return -1;

    int result;
    std::memcpy(&result, data, sizeof(int));
    return result;
}

/**
 * Reads an integer acknowledgment from the socket.
 */
int read_int(int fd) {
    int val;
    if (read(fd, &val, sizeof(val)) != sizeof(val)) return -1;
    return val;
}

/**
 * Writes an integer command/ID to the socket.
 */
void write_int(int fd, int val) {
    if (fd < 0) return;
    (void)write(fd, &val, sizeof(val));
}

bool read_fully(int fd, void *data, size_t size) {
    auto *ptr = static_cast<char *>(data);
    size_t done = 0;
    while (done < size) {
        ssize_t r = read(fd, ptr + done, size - done);
        if (r <= 0) return false;
        done += static_cast<size_t>(r);
    }
    return true;
}

bool write_fully(int fd, const void *data, size_t size) {
    const auto *ptr = static_cast<const char *>(data);
    size_t done = 0;
    while (done < size) {
        ssize_t r = write(fd, ptr + done, size - done);
        if (r <= 0) return false;
        done += static_cast<size_t>(r);
    }
    return true;
}

int connect_server() {
    struct sockaddr_un sock = {};
    sock.sun_family = AF_UNIX;
    std::strncpy(sock.sun_path + 1, kSockName, sizeof(sock.sun_path) - 2);
    socklen_t len = sizeof(sock.sun_family) + strlen(kSockName) + 1;

    int sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock_fd < 0) {
        PLOGE("socket");
        return -1;
    }
    if (connect(sock_fd, reinterpret_cast<struct sockaddr *>(&sock), len)) {
        PLOGE("failed to connect to %s", sock.sun_path + 1);
        close(sock_fd);
        return -1;
    }
    return sock_fd;
}

bool write_socket_string(int fd, const std::string &value) {
    uint32_t length = static_cast<uint32_t>(value.size());
    unsigned char header[4] = {
        static_cast<unsigned char>((length >> 24) & 0xff),
        static_cast<unsigned char>((length >> 16) & 0xff),
        static_cast<unsigned char>((length >> 8) & 0xff),
        static_cast<unsigned char>(length & 0xff),
    };
    return write_fully(fd, header, sizeof(header)) &&
           (value.empty() || write_fully(fd, value.data(), value.size()));
}

int request_fd(int id) {
    int sock_fd = connect_server();
    if (sock_fd < 0) return -1;

    write_int(sock_fd, id);
    int fd = recv_fd(sock_fd);
    read_int(sock_fd);
    close(sock_fd);
    return fd;
}

std::string arg_value(const char *arg, const char *prefix) {
    if (arg == nullptr) return {};
    size_t prefix_len = std::strlen(prefix);
    if (std::strncmp(arg, prefix, prefix_len) != 0) return {};
    return std::string(arg + prefix_len);
}

std::string read_link_path(const std::string &path) {
    std::vector<char> buffer(4096);
    ssize_t size = readlink(path.c_str(), buffer.data(), buffer.size() - 1);
    if (size < 0) return {};
    buffer[static_cast<size_t>(size)] = '\0';
    return std::string(buffer.data());
}

std::string fd_arg_path(const char *arg, const char *prefix) {
    auto fd_value = arg_value(arg, prefix);
    if (fd_value.empty()) return {};
    return read_link_path("/proc/self/fd/" + fd_value);
}

bool has_prefix(const char *arg, const char *prefix) {
    return arg != nullptr && std::strncmp(arg, prefix, std::strlen(prefix)) == 0;
}

bool query_noinline(const std::string &apk_path) {
    int sock_fd = connect_server();
    if (sock_fd < 0) {
        LOGW("noinline query failed: daemon unavailable, defaulting to false for apk=%s",
             apk_path.c_str());
        return false;
    }

    unsigned char cmd = kCmdIsNoInlineNeeded;
    bool ok = write_fully(sock_fd, &cmd, sizeof(cmd)) && write_socket_string(sock_fd, apk_path);
    unsigned char result = 1;
    if (ok) ok = read_fully(sock_fd, &result, sizeof(result));
    close(sock_fd);

    if (!ok) {
        LOGW("noinline query failed, defaulting to false for apk=%s", apk_path.c_str());
        return false;
    }
    return result != 0;
}

void record_dex2oat(const std::string &apk_path,
                    const std::string &odex_path,
                    const std::string &real_path) {
    int sock_fd = connect_server();
    if (sock_fd < 0) return;

    unsigned char cmd = kCmdRecordDex2Oat;
    bool ok = write_fully(sock_fd, &cmd, sizeof(cmd)) &&
              write_socket_string(sock_fd, apk_path) &&
              write_socket_string(sock_fd, odex_path) &&
              write_socket_string(sock_fd, real_path);
    unsigned char ack = 0;
    if (ok) (void)read_fully(sock_fd, &ack, sizeof(ack));
    close(sock_fd);
}

}  // namespace

int main(int argc, char **argv) {
    LOGD("dex2oat wrapper ppid=%d", getppid());

    // 1. Get original dex2oat binary FD
    bool is_debug = (argv[0] != nullptr && std::strstr(argv[0], "dex2oatd") != nullptr);
    int stock_fd = request_fd(get_id_vec(LP_SELECT(false, true), is_debug));

    // 2. Get liboat_hook.so FD
    int hooker_fd = request_fd(LP_SELECT(4, 5));

    if (hooker_fd == -1) {
        LOGE("failed to read liboat_hook.so");
    } else {
        int mem_fd = syscall(__NR_memfd_create, "liboat_hook_memfd", 0);
        if (mem_fd >= 0) {
            // Get the exact size of the original library
            LOGD("Copying %d as mem_fd %d", hooker_fd, mem_fd);
            struct stat st;
            if (fstat(hooker_fd, &st) == 0) {
                // Tell the kernel to copy the entire file directly to the memfd
                off_t offset = 0;
                sendfile(mem_fd, hooker_fd, &offset, st.st_size);

                // Swap the old FD with the new memfd
                close(hooker_fd);
                hooker_fd = mem_fd;
            } else {
                PLOGE("fstat failed");
                close(mem_fd);
            }
        } else {
            PLOGE("memfd_create failed, falling back to original fd");
        }
    }

    if (stock_fd == -1) {
        LOGE("failed to read original dex2oat");
        return 1;
    }

    std::string apk_path;
    std::string odex_path;
    for (int i = 1; i < argc; ++i) {
        if (apk_path.empty()) apk_path = arg_value(argv[i], "--dex-file=");
        if (apk_path.empty()) apk_path = arg_value(argv[i], "--zip-location=");
        if (apk_path.empty()) apk_path = fd_arg_path(argv[i], "--zip-fd=");
        if (odex_path.empty()) odex_path = arg_value(argv[i], "--oat-file=");
        if (odex_path.empty()) odex_path = fd_arg_path(argv[i], "--oat-fd=");
    }

    bool noinline = query_noinline(apk_path);
    LOGI("dex2oat wrapper invoked: apk_path=%s odex_path=%s noinline=%s",
         apk_path.c_str(),
         odex_path.c_str(),
         noinline ? "true" : "false");

    // Prepare arguments for execve
    // Logic: [linker] [/proc/self/fd/stock_fd] [original_args...] [optional no-inline flag]
    std::vector<const char *> exec_argv;

    const char *linker_path =
        LP_SELECT("/apex/com.android.runtime/bin/linker", "/apex/com.android.runtime/bin/linker64");

    char stock_fd_path[64];
    std::snprintf(stock_fd_path, sizeof(stock_fd_path), "/proc/self/fd/%d", stock_fd);

    exec_argv.push_back(linker_path);
    exec_argv.push_back(stock_fd_path);

    bool removed_inline_max_code_units = false;
    bool injected_inline_max_code_units = false;

    // Append original arguments starting from argv[1]
    for (int i = 1; i < argc; ++i) {
        if (has_prefix(argv[i], "--inline-max-code-units=")) {
            removed_inline_max_code_units = true;
            continue;
        }
        exec_argv.push_back(argv[i]);
    }

    if (noinline) {
        LOGI("dex2oat wrapper: injecting --inline-max-code-units=0");
        exec_argv.push_back("--inline-max-code-units=0");
        injected_inline_max_code_units = true;
    }
    LOGI("dex2oat wrapper: removed_existing_inline_max_code_units=%s injected_inline_max_code_units=%s",
         removed_inline_max_code_units ? "true" : "false",
         injected_inline_max_code_units ? "true" : "false");
    exec_argv.push_back(nullptr);

    // Setup Environment variables
    // Clear LD_LIBRARY_PATH to let the linker use internal config
    unsetenv("LD_LIBRARY_PATH");

    if (noinline && hooker_fd != -1) {
        std::string preload_path = "/proc/self/fd/" + std::to_string(hooker_fd);
        LOGI("dex2oat wrapper: LD_PRELOAD set to %s", preload_path.c_str());
        setenv("LD_PRELOAD", preload_path.c_str(), 1);
    } else {
        LOGI("dex2oat wrapper: LD_PRELOAD not set");
        unsetenv("LD_PRELOAD");
    }

    // Pass original argv[0] as DEX2OAT_CMD
    if (argv[0]) {
        setenv("DEX2OAT_CMD", argv[0], 1);
        LOGD("DEX2OAT_CMD set to %s", argv[0]);
    }

    LOGI("Executing via linker: %s executing %s", linker_path, stock_fd_path);

    pid_t pid = fork();
    if (pid == 0) {
        execve(linker_path, const_cast<char *const *>(exec_argv.data()), environ);
        PLOGE("execve failed");
        _exit(127);
    }
    if (pid < 0) {
        PLOGE("fork failed");
        return 2;
    }

    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        PLOGE("waitpid failed");
        return 2;
    }

    record_dex2oat(apk_path, odex_path, stock_fd_path);

    if (WIFEXITED(status)) {
        int exit_code = WEXITSTATUS(status);
        LOGI("dex2oat wrapper: child dex2oat exit code=%d", exit_code);
        return exit_code;
    }
    if (WIFSIGNALED(status)) {
        LOGW("dex2oat wrapper: child dex2oat killed by signal=%d", WTERMSIG(status));
        return 128 + WTERMSIG(status);
    }
    return 2;

}
