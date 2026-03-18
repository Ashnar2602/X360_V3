#define _GNU_SOURCE

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *kDefaultSentinelPath = "/tmp/fex_dyn_hello_ok.json";
static const char *kSuccessMarker = "X360_DYN_HELLO_OK";

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

int main(int argc, char **argv) {
  const char *sentinel_path = kDefaultSentinelPath;
  for (int index = 1; index < argc; ++index) {
    if (strncmp(argv[index], "--sentinel=", 11) == 0) {
      sentinel_path = argv[index] + 11;
    }
  }

  char current_working_directory[PATH_MAX];
  if (getcwd(current_working_directory, sizeof(current_working_directory)) == NULL) {
    snprintf(current_working_directory, sizeof(current_working_directory), "<getcwd:%s>", strerror(errno));
  }

  printf("%s\n", kSuccessMarker);
  printf("cwd=%s\n", current_working_directory);
  for (int index = 0; index < argc; ++index) {
    printf("argv[%d]=%s\n", index, argv[index]);
  }
  fflush(stdout);

  FILE *sentinel = fopen(sentinel_path, "w");
  if (sentinel == NULL) {
    fprintf(stderr, "failed to open sentinel %s: %s\n", sentinel_path, strerror(errno));
    return 2;
  }

  fputs("{\"marker\":\"", sentinel);
  fputs(kSuccessMarker, sentinel);
  fputs("\",\"cwd\":\"", sentinel);
  JsonWriteEscaped(sentinel, current_working_directory);
  fputs("\",\"argc\":", sentinel);
  fprintf(sentinel, "%d", argc);
  fputs("}\n", sentinel);
  fclose(sentinel);

  return 0;
}
