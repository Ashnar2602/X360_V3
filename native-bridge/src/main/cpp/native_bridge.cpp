#include <android/log.h>
#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <iomanip>
#include <poll.h>
#include <sstream>
#include <string>
#include <sys/ioctl.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr const char* kTag = "NativeBridge";
constexpr const char* kKgslDevicePath = "/dev/kgsl-3d0";

constexpr unsigned int KGSL_PROP_DEVICE_INFO = 0x1;
constexpr unsigned int KGSL_PROP_UCHE_GMEM_VADDR = 0x13;
constexpr unsigned int KGSL_PROP_HIGHEST_BANK_BIT = 0x17;
constexpr unsigned int KGSL_PROP_UBWC_MODE = 0x1B;
constexpr unsigned int KGSL_PROP_QUERY_CAPABILITIES = 0x27;
constexpr unsigned int KGSL_PROP_GPU_MODEL = 0x29;
constexpr unsigned int KGSL_PROP_VK_DEVICE_ID = 0x2A;
constexpr unsigned int KGSL_PROP_GPU_VA64_SIZE = 0x2C;
constexpr unsigned int KGSL_PROP_IS_RAYTRACING_ENABLED = 0x2D;
constexpr unsigned int KGSL_PROP_UCHE_TRAP_BASE = 0x2F;

constexpr std::uint32_t KGSL_QUERY_CAPS_PROPERTIES = 1;
constexpr std::uint32_t KGSL_UBWC_NONE = 0;
constexpr std::uint32_t KGSL_UBWC_1_0 = 1;
constexpr std::uint32_t KGSL_UBWC_2_0 = 2;
constexpr std::uint32_t KGSL_UBWC_3_0 = 3;
constexpr std::uint32_t KGSL_UBWC_4_0 = 4;

struct kgsl_devinfo {
  unsigned int device_id;
  unsigned int chip_id;
  unsigned int mmu_enabled;
  unsigned long gmem_gpubaseaddr;
  unsigned int gpu_id;
  std::size_t gmem_sizebytes;
};

struct kgsl_device_getproperty {
  unsigned int type;
  void* value;
  std::size_t sizebytes;
};

struct kgsl_capabilities_properties {
  std::uint64_t list;
  std::uint32_t count;
};

struct kgsl_capabilities {
  std::uint64_t data;
  std::uint64_t size;
  std::uint32_t querytype;
};

struct kgsl_gpu_model {
  char gpu_model[32];
};

#define KGSL_IOC_TYPE 0x09
#define IOCTL_KGSL_DEVICE_GETPROPERTY \
  _IOWR(KGSL_IOC_TYPE, 0x2, struct kgsl_device_getproperty)

std::string JStringToString(JNIEnv* env, jstring value) {
  if (value == nullptr) {
    return {};
  }

  const char* raw = env->GetStringUTFChars(value, nullptr);
  std::string converted = raw == nullptr ? "" : raw;
  if (raw != nullptr) {
    env->ReleaseStringUTFChars(value, raw);
  }
  return converted;
}

jstring StringToJString(JNIEnv* env, const std::string& value) {
  return env->NewStringUTF(value.c_str());
}

std::string JsonEscape(const std::string& value) {
  std::ostringstream escaped;
  for (const unsigned char ch : value) {
    switch (ch) {
      case '\\':
      case '"':
        escaped << '\\' << static_cast<char>(ch);
        break;
      case '\n':
        escaped << "\\n";
        break;
      case '\r':
        escaped << "\\r";
        break;
      case '\t':
        escaped << "\\t";
        break;
      default:
        escaped << static_cast<char>(ch);
        break;
    }
  }
  return escaped.str();
}

std::string Hex(std::uint64_t value) {
  std::ostringstream stream;
  stream << "0x" << std::hex << std::nouppercase << value;
  return stream.str();
}

int SafeIoctl(int fd, unsigned long request, void* arg) {
  int result;
  do {
    result = ioctl(fd, request, arg);
  } while (result == -1 && (errno == EINTR || errno == EAGAIN));
  return result;
}

template <typename T>
bool GetKgslProp(int fd, unsigned int type, T* value, int* error_out) {
  kgsl_device_getproperty request = {
      .type = type,
      .value = value,
      .sizebytes = sizeof(T),
  };
  if (SafeIoctl(fd, IOCTL_KGSL_DEVICE_GETPROPERTY, &request) == 0) {
    if (error_out != nullptr) {
      *error_out = 0;
    }
    return true;
  }

  if (error_out != nullptr) {
    *error_out = errno;
  }
  return false;
}

template <typename T>
std::string DescribeScalarProp(int fd, unsigned int type) {
  T value{};
  int prop_error = 0;
  if (!GetKgslProp(fd, type, &value, &prop_error)) {
    std::ostringstream failure;
    failure << "{\"ok\":false,\"errno\":" << prop_error << ",\"error\":\""
            << JsonEscape(std::strerror(prop_error)) << "\"}";
    return failure.str();
  }

  std::ostringstream success;
  success << "{\"ok\":true,\"value\":" << value;
  if constexpr (sizeof(T) == sizeof(std::uint64_t)) {
    success << ",\"hex\":\"" << Hex(static_cast<std::uint64_t>(value)) << "\"";
  }
  success << "}";
  return success.str();
}

std::string DescribeSupportedKgslProperties(int fd) {
  kgsl_capabilities_properties properties = {};
  kgsl_capabilities capability_query = {
      .data = reinterpret_cast<std::uint64_t>(&properties),
      .size = sizeof(properties),
      .querytype = KGSL_QUERY_CAPS_PROPERTIES,
  };

  int first_error = 0;
  if (!GetKgslProp(fd, KGSL_PROP_QUERY_CAPABILITIES, &capability_query, &first_error)) {
    std::ostringstream failure;
    failure << "{\"ok\":false,\"errno\":" << first_error << ",\"error\":\""
            << JsonEscape(std::strerror(first_error)) << "\"}";
    return failure.str();
  }

  std::vector<std::uint32_t> values(properties.count);
  properties.list = reinterpret_cast<std::uint64_t>(values.data());
  capability_query.data = reinterpret_cast<std::uint64_t>(&properties);

  int second_error = 0;
  if (properties.count > 0 &&
      !GetKgslProp(fd, KGSL_PROP_QUERY_CAPABILITIES, &capability_query, &second_error)) {
    std::ostringstream failure;
    failure << "{\"ok\":false,\"errno\":" << second_error << ",\"error\":\""
            << JsonEscape(std::strerror(second_error)) << "\"}";
    return failure.str();
  }

  std::ostringstream result;
  result << "{\"ok\":true,\"count\":" << properties.count << ",\"values\":[";
  for (std::size_t index = 0; index < values.size(); ++index) {
    if (index > 0) {
      result << ',';
    }
    result << values[index];
  }
  result << "]}";
  return result.str();
}

std::string DescribeKgslDeviceInfo(int fd) {
  kgsl_devinfo info = {};
  int prop_error = 0;
  if (!GetKgslProp(fd, KGSL_PROP_DEVICE_INFO, &info, &prop_error)) {
    std::ostringstream failure;
    failure << "{\"ok\":false,\"errno\":" << prop_error << ",\"error\":\""
            << JsonEscape(std::strerror(prop_error)) << "\"}";
    return failure.str();
  }

  std::ostringstream result;
  result << "{\"ok\":true"
         << ",\"device_id\":" << info.device_id
         << ",\"chip_id\":" << info.chip_id
         << ",\"chip_id_hex\":\"" << Hex(info.chip_id) << "\""
         << ",\"gpu_id\":" << info.gpu_id
         << ",\"mmu_enabled\":" << (info.mmu_enabled != 0 ? "true" : "false")
         << ",\"gmem_gpubaseaddr\":" << info.gmem_gpubaseaddr
         << ",\"gmem_gpubaseaddr_hex\":\"" << Hex(info.gmem_gpubaseaddr) << "\""
         << ",\"gmem_sizebytes\":" << info.gmem_sizebytes
         << "}";
  return result.str();
}

std::string DescribeKgslGpuModel(int fd) {
  kgsl_gpu_model model = {};
  int prop_error = 0;
  if (!GetKgslProp(fd, KGSL_PROP_GPU_MODEL, &model, &prop_error)) {
    std::ostringstream failure;
    failure << "{\"ok\":false,\"errno\":" << prop_error << ",\"error\":\""
            << JsonEscape(std::strerror(prop_error)) << "\"}";
    return failure.str();
  }

  return "{\"ok\":true,\"value\":\"" + JsonEscape(model.gpu_model) + "\"}";
}

std::string UbwcModeName(std::uint32_t mode) {
  switch (mode) {
    case KGSL_UBWC_NONE:
      return "none";
    case KGSL_UBWC_1_0:
      return "1.0";
    case KGSL_UBWC_2_0:
      return "2.0";
    case KGSL_UBWC_3_0:
      return "3.0";
    case KGSL_UBWC_4_0:
      return "4.0";
    default:
      return "unknown";
  }
}

std::string DescribeUbwcMode(int fd) {
  std::uint32_t mode = 0;
  int prop_error = 0;
  if (!GetKgslProp(fd, KGSL_PROP_UBWC_MODE, &mode, &prop_error)) {
    std::ostringstream failure;
    failure << "{\"ok\":false,\"errno\":" << prop_error << ",\"error\":\""
            << JsonEscape(std::strerror(prop_error)) << "\"}";
    return failure.str();
  }

  std::ostringstream result;
  result << "{\"ok\":true,\"value\":" << mode << ",\"name\":\""
         << UbwcModeName(mode) << "\"}";
  return result.str();
}

std::string InspectKgslPropertiesJson() {
  const int fd = open(kKgslDevicePath, O_RDWR | O_CLOEXEC);
  if (fd < 0) {
    std::ostringstream failure;
    failure << "{\"device_path\":\"" << kKgslDevicePath << "\",\"open_ok\":false,\"errno\":"
            << errno << ",\"error\":\"" << JsonEscape(std::strerror(errno)) << "\"}";
    return failure.str();
  }

  std::ostringstream result;
  result << "{"
         << "\"device_path\":\"" << kKgslDevicePath << "\""
         << ",\"open_ok\":true"
         << ",\"supported_properties\":" << DescribeSupportedKgslProperties(fd)
         << ",\"device_info\":" << DescribeKgslDeviceInfo(fd)
         << ",\"gpu_model\":" << DescribeKgslGpuModel(fd)
         << ",\"uche_gmem_vaddr\":" << DescribeScalarProp<std::uint64_t>(fd, KGSL_PROP_UCHE_GMEM_VADDR)
         << ",\"highest_bank_bit\":" << DescribeScalarProp<std::uint32_t>(fd, KGSL_PROP_HIGHEST_BANK_BIT)
         << ",\"ubwc_mode\":" << DescribeUbwcMode(fd)
         << ",\"uche_trap_base\":" << DescribeScalarProp<std::uint64_t>(fd, KGSL_PROP_UCHE_TRAP_BASE)
         << ",\"gpu_va64_size\":" << DescribeScalarProp<std::uint64_t>(fd, KGSL_PROP_GPU_VA64_SIZE)
         << ",\"vk_device_id\":" << DescribeScalarProp<std::uint32_t>(fd, KGSL_PROP_VK_DEVICE_ID)
         << ",\"raytracing_enabled\":" << DescribeScalarProp<std::uint32_t>(fd, KGSL_PROP_IS_RAYTRACING_ENABLED)
         << "}";

  close(fd);
  return result.str();
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_healthCheck(JNIEnv* env, jobject /* this */) {
  __android_log_print(ANDROID_LOG_INFO, kTag, "healthCheck()");
  return StringToJString(env, "native-bridge:ok");
}

extern "C" JNIEXPORT jstring JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_bootstrapStub(JNIEnv* env,
                                                                 jobject /* this */,
                                                                 jstring runtimeRoot_) {
  const std::string runtimeRoot = JStringToString(env, runtimeRoot_);
  const std::filesystem::path bootstrapFile =
      std::filesystem::path(runtimeRoot) / "rootfs" / "tmp" / "native_bridge_ready.txt";

  std::filesystem::create_directories(bootstrapFile.parent_path());
  std::ofstream output(bootstrapFile, std::ios::trunc);
  output << "native-bridge bootstrap placeholder\n";
  output << "future-use=fex-loader-hook\n";
  output.close();

  __android_log_print(ANDROID_LOG_INFO, kTag, "bootstrapStub(%s)", runtimeRoot.c_str());
  return StringToJString(env, bootstrapFile.string());
}

extern "C" JNIEXPORT jstring JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_describeSurfaceHookPlaceholder(
    JNIEnv* env,
    jobject /* this */,
    jstring rootfsTmpPath_) {
  const std::string rootfsTmpPath = JStringToString(env, rootfsTmpPath_);
  const std::filesystem::path pointerFile =
      std::filesystem::path(rootfsTmpPath) / "anative_window.ptr";
  const std::filesystem::path frameBufferFile =
      std::filesystem::path(rootfsTmpPath) / "xenia_fb";

  const std::string message =
      "reserved:" + pointerFile.string() + "|" + frameBufferFile.string();
  __android_log_print(ANDROID_LOG_INFO, kTag, "describeSurfaceHookPlaceholder(%s)",
                      rootfsTmpPath.c_str());
  return StringToJString(env, message);
}

extern "C" JNIEXPORT jstring JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_inspectKgslProperties(JNIEnv* env,
                                                                         jobject /* this */) {
  const std::string report = InspectKgslPropertiesJson();
  __android_log_print(ANDROID_LOG_INFO, kTag, "inspectKgslProperties=%s", report.c_str());
  return StringToJString(env, report);
}

extern "C" JNIEXPORT jint JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_adoptFdForExec(JNIEnv* /* env */,
                                                                  jobject /* this */,
                                                                  jint raw_fd,
                                                                  jint minimum_fd) {
  const int duplicated_fd = fcntl(raw_fd, F_DUPFD, minimum_fd);
  const int duplicate_errno = errno;
  close(raw_fd);
  if (duplicated_fd < 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag,
                        "adoptFdForExec(raw=%d, min=%d) failed: errno=%d",
                        raw_fd, minimum_fd, duplicate_errno);
    return -duplicate_errno;
  }

  const int flags = fcntl(duplicated_fd, F_GETFD);
  if (flags >= 0) {
    fcntl(duplicated_fd, F_SETFD, flags & ~FD_CLOEXEC);
  }

  __android_log_print(ANDROID_LOG_INFO, kTag,
                      "adoptFdForExec(raw=%d, min=%d) -> %d",
                      raw_fd, minimum_fd, duplicated_fd);
  return duplicated_fd;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_restoreStdinAfterExec(
    JNIEnv* /* env */,
    jobject /* this */,
    jint saved_fd) {
  if (saved_fd < 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag,
                        "restoreStdinAfterExec(%d) ignored invalid saved fd", saved_fd);
    return JNI_FALSE;
  }

  if (dup2(saved_fd, STDIN_FILENO) < 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag,
                        "restoreStdinAfterExec(%d) dup2 failed: errno=%d",
                        saved_fd, errno);
    close(saved_fd);
    return JNI_FALSE;
  }

  const int flags = fcntl(STDIN_FILENO, F_GETFD);
  if (flags >= 0) {
    fcntl(STDIN_FILENO, F_SETFD, flags & ~FD_CLOEXEC);
  }

  if (close(saved_fd) != 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag,
                        "restoreStdinAfterExec(%d) close failed: errno=%d",
                        saved_fd, errno);
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_remapFdToStdinForExec(
    JNIEnv* /* env */,
    jobject /* this */,
    jint fd) {
  if (fd < 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag,
                        "remapFdToStdinForExec(%d) invalid descriptor", fd);
    return -EINVAL;
  }

  const int saved_stdin = dup(STDIN_FILENO);
  const int saved_errno = errno;
  if (saved_stdin < 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag,
                        "remapFdToStdinForExec(%d) dup stdin failed: errno=%d",
                        fd, saved_errno);
    return -saved_errno;
  }

  if (dup2(fd, STDIN_FILENO) < 0) {
    const int dup_errno = errno;
    __android_log_print(ANDROID_LOG_ERROR, kTag,
                        "remapFdToStdinForExec(%d) dup2 failed: errno=%d",
                        fd, dup_errno);
    close(saved_stdin);
    return -dup_errno;
  }

  const int flags = fcntl(STDIN_FILENO, F_GETFD);
  if (flags >= 0) {
    fcntl(STDIN_FILENO, F_SETFD, flags & ~FD_CLOEXEC);
  }

  __android_log_print(ANDROID_LOG_INFO, kTag,
                      "remapFdToStdinForExec(%d) saved stdin as %d",
                      fd, saved_stdin);
  return saved_stdin;
}

extern "C" JNIEXPORT jint JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_pollFdReadable(
    JNIEnv* /* env */,
    jobject /* this */,
    jint fd,
    jint timeout_ms) {
  if (fd < 0) {
    return -EINVAL;
  }

  pollfd descriptor = {
      .fd = fd,
      .events = static_cast<short>(POLLIN | POLLERR | POLLHUP),
      .revents = 0,
  };
  int result;
  do {
    result = poll(&descriptor, 1, timeout_ms);
  } while (result < 0 && errno == EINTR);

  if (result < 0) {
    return -errno;
  }
  if (result == 0) {
    return 0;
  }
  return descriptor.revents;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_emu_x360_mobile_dev_nativebridge_NativeBridge_closeFd(JNIEnv* /* env */,
                                                           jobject /* this */,
                                                           jint fd) {
  const int result = close(fd);
  if (result != 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "closeFd(%d) failed: errno=%d",
                        fd, errno);
    return JNI_FALSE;
  }
  return JNI_TRUE;
}
