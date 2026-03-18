#define _GNU_SOURCE

#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

static const char *kDefaultSentinelPath = "/tmp/fex_vulkan_probe.json";
static const char *kSuccessMarker = "X360_VK_PROBE_OK";

static const char *GetDriverMode(void) {
  const char *driver_mode = getenv("X360_DRIVER_MODE");
  if (driver_mode == NULL || driver_mode[0] == '\0') {
    return "unknown";
  }
  return driver_mode;
}

static void JsonWriteEscaped(FILE *file, const char *value) {
  for (const unsigned char *cursor = (const unsigned char *)value; *cursor != '\0'; ++cursor) {
    switch (*cursor) {
      case '\\':
      case '"':
        fputc('\\', file);
        fputc(*cursor, file);
        break;
      case '\n':
        fputs("\\n", file);
        break;
      case '\r':
        fputs("\\r", file);
        break;
      case '\t':
        fputs("\\t", file);
        break;
      default:
        fputc(*cursor, file);
        break;
    }
  }
}

static void WriteFailureSentinel(const char *path, VkResult result) {
  const char *driver_mode = GetDriverMode();
  FILE *sentinel = fopen(path, "w");
  if (sentinel == NULL) {
    fprintf(stderr, "failed to open sentinel %s: %s\n", path, strerror(errno));
    return;
  }

  fprintf(
      sentinel,
      "{\"marker\":\"\",\"driver_mode\":\"%s\",\"instance_ok\":false,\"device_count\":0,\"vk_result\":%d,"
      "\"vendor_id\":0,\"device_id\":0,\"device_name\":\"\",\"api_version\":0,\"device_names\":[]}\n",
      driver_mode,
      result);
  fclose(sentinel);
}

int main(int argc, char **argv) {
  const char *sentinel_path = kDefaultSentinelPath;
  const char *driver_mode = GetDriverMode();
  for (int index = 1; index < argc; ++index) {
    if (strncmp(argv[index], "--sentinel=", 11) == 0) {
      sentinel_path = argv[index] + 11;
    }
  }

  VkApplicationInfo app_info = {
      .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "x360-vk-probe",
      .applicationVersion = VK_MAKE_API_VERSION(0, 0, 1, 0),
      .pEngineName = "x360-v3",
      .engineVersion = VK_MAKE_API_VERSION(0, 0, 1, 0),
      .apiVersion = VK_API_VERSION_1_1,
  };
  VkInstanceCreateInfo create_info = {
      .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &app_info,
  };

  VkInstance instance = VK_NULL_HANDLE;
  VkResult result = vkCreateInstance(&create_info, NULL, &instance);
  if (result != VK_SUCCESS) {
    fprintf(stderr, "vkCreateInstance failed: %d\n", result);
    WriteFailureSentinel(sentinel_path, result);
    return 2;
  }

  uint32_t device_count = 0;
  result = vkEnumeratePhysicalDevices(instance, &device_count, NULL);
  if (result != VK_SUCCESS) {
    fprintf(stderr, "vkEnumeratePhysicalDevices(count) failed: %d\n", result);
    WriteFailureSentinel(sentinel_path, result);
    vkDestroyInstance(instance, NULL);
    return 3;
  }

  VkPhysicalDevice *devices = NULL;
  if (device_count > 0) {
    devices = calloc(device_count, sizeof(VkPhysicalDevice));
    if (devices == NULL) {
      fprintf(stderr, "failed to allocate physical device array\n");
      WriteFailureSentinel(sentinel_path, VK_ERROR_OUT_OF_HOST_MEMORY);
      vkDestroyInstance(instance, NULL);
      return 4;
    }

    result = vkEnumeratePhysicalDevices(instance, &device_count, devices);
    if (result != VK_SUCCESS) {
      fprintf(stderr, "vkEnumeratePhysicalDevices(list) failed: %d\n", result);
      WriteFailureSentinel(sentinel_path, result);
      free(devices);
      vkDestroyInstance(instance, NULL);
      return 5;
    }
  }

  printf("%s\n", kSuccessMarker);
  printf("driver_mode=%s\n", driver_mode);
  printf("physical_device_count=%u\n", device_count);

  uint32_t first_vendor_id = 0;
  uint32_t first_device_id = 0;
  uint32_t first_api_version = 0;
  const char *first_device_name = "";
  if (device_count > 0) {
    VkPhysicalDeviceProperties first_properties;
    vkGetPhysicalDeviceProperties(devices[0], &first_properties);
    first_vendor_id = first_properties.vendorID;
    first_device_id = first_properties.deviceID;
    first_api_version = first_properties.apiVersion;
    first_device_name = first_properties.deviceName;
  }

  FILE *sentinel = fopen(sentinel_path, "w");
  if (sentinel == NULL) {
    fprintf(stderr, "failed to open sentinel %s: %s\n", sentinel_path, strerror(errno));
    free(devices);
    vkDestroyInstance(instance, NULL);
    return 6;
  }

  fprintf(
      sentinel,
      "{\"marker\":\"%s\",\"driver_mode\":\"%s\",\"instance_ok\":true,\"device_count\":%u,"
      "\"vendor_id\":%u,\"device_id\":%u,\"device_name\":\"",
      kSuccessMarker,
      driver_mode,
      device_count,
      first_vendor_id,
      first_device_id);
  JsonWriteEscaped(sentinel, first_device_name);
  fprintf(sentinel, "\",\"api_version\":%u,\"device_names\":[", first_api_version);

  for (uint32_t index = 0; index < device_count; ++index) {
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(devices[index], &properties);
    printf("device[%u]=%s\n", index, properties.deviceName);
    printf("vendor_id[%u]=%u\n", index, properties.vendorID);
    printf("device_id[%u]=%u\n", index, properties.deviceID);
    printf("api_version[%u]=%u\n", index, properties.apiVersion);
    if (index > 0) {
      fputc(',', sentinel);
    }
    fputc('"', sentinel);
    JsonWriteEscaped(sentinel, properties.deviceName);
    fputc('"', sentinel);
  }
  fputs("]}\n", sentinel);
  fclose(sentinel);

  free(devices);
  vkDestroyInstance(instance, NULL);
  return device_count > 0 ? 0 : 7;
}
