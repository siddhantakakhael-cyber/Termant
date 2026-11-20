// Reads pictures on standard input and writes what Mirage sees in each, one line of JSON per picture.
//   reader <mirage.cact> [options json]
// A picture is a text line "<width> <height>\n" followed by width*height*3 bytes of packed RGB.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "cactus.h"

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000.0 + t.tv_nsec / 1e6; }

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "usage: reader <mirage.cact> [options]\n"); return 2; }
  const char* options = argc > 2 ? argv[2] : "{\"sections\":[\"text\"]}";
  FILE* f = fopen(argv[1], "rb");
  if (!f) { fprintf(stderr, "cannot open the model\n"); return 2; }
  fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
  unsigned char* model = malloc(n);
  if (fread(model, 1, n, f) != (size_t)n) { fprintf(stderr, "cannot read the model\n"); return 2; }
  fclose(f);
  double t0 = now_ms();
  if (cactus_load(model, (unsigned long long)n) < 0) { fprintf(stderr, "model: %s\n", cactus_last_error()); return 3; }
  if (!(cactus_models() & CACTUS_VISION)) { fprintf(stderr, "not a vision model\n"); return 3; }
  fprintf(stderr, "loaded in %.0f ms\n", now_ms() - t0);
  int capacity = 4 * 1024 * 1024;
  char* out = malloc(capacity);
  char line[64];
  while (fgets(line, sizeof line, stdin)) {
    int w = 0, h = 0;
    if (sscanf(line, "%d %d", &w, &h) != 2 || w < 1 || h < 1 || w > 8192 || h > 8192) { fprintf(stderr, "bad header\n"); return 4; }
    size_t size = (size_t)w * h * 3;
    unsigned char* rgb = malloc(size);
    if (fread(rgb, 1, size, stdin) != size) { fprintf(stderr, "short picture\n"); return 4; }
    double t = now_ms();
    int r = cactus_describe(rgb, w, h, options, NULL, NULL, out, capacity);
    free(rgb);
    if (r < 0) { printf("{\"error\":\"failed\"}\n"); fprintf(stderr, "describe: %s\n", cactus_last_error()); }
    else { for (char* p = out; *p; p++) if (*p == '\n' || *p == '\r') *p = ' '; printf("%s\n", out); }
    fflush(stdout);
    fprintf(stderr, "%dx%d in %.0f ms\n", w, h, now_ms() - t);
  }
  return 0;
}
