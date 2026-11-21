// The reader of pictures: Termant sends it pictures, it answers with what the
// Mirage model sees in each.
//
//   libmirage.so <mirage.cact> [options]
//
// Without options it gives the writing only, with a box around every piece
// (for reading the screen). With the options "{}" it gives all it sees: the
// scene, the things with a box around each, the writing, and what the things
// do with each other (for looking at a photo).
//
// On standard input, for each picture: a line "<width> <height>\n" and then
// width*height*3 bytes of packed RGB. On standard output: one line of JSON per
// picture, in the same order. It runs the Cactus engine (Apache 2.0), which is
// linked in; see ../tools/build_mirage.sh.
#include <stdio.h>
#include <stdlib.h>

int cactus_load(const unsigned char* cact, unsigned long long n);
int cactus_models(void);
const char* cactus_last_error(void);
int cactus_describe(const unsigned char* rgb, int width, int height, const char* options,
                    const char* language, const char* keywords, char* out, int out_capacity);

#define VISION 4
#define ANSWER (4 * 1024 * 1024)

static int fail(int code, const char* what, const char* why) {
  fprintf(stderr, "%s%s%s\n", what, why ? ": " : "", why ? why : "");
  return code;
}

int main(int argc, char** argv) {
  if (argc < 2) return fail(2, "usage: libmirage.so <mirage.cact> [options]", NULL);
  const char* options = argc > 2 ? argv[2] : "{\"sections\":[\"text\"]}";
  FILE* f = fopen(argv[1], "rb");
  if (!f) return fail(2, "the model file cannot be opened", NULL);
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  unsigned char* model = n > 0 ? malloc((size_t)n) : NULL;
  if (!model || fread(model, 1, (size_t)n, f) != (size_t)n) return fail(2, "the model file cannot be read", NULL);
  fclose(f);
  if (cactus_load(model, (unsigned long long)n) < 0) return fail(3, "the model did not load", cactus_last_error());
  if (!(cactus_models() & VISION)) return fail(3, "the model file is not a vision model", NULL);

  char* answer = malloc(ANSWER);
  char line[64];
  while (fgets(line, sizeof line, stdin)) {
    int w = 0, h = 0;
    if (sscanf(line, "%d %d", &w, &h) != 2 || w < 8 || h < 8 || w > 4096 || h > 4096) return fail(4, "bad picture size", NULL);
    size_t size = (size_t)w * (size_t)h * 3;
    unsigned char* rgb = malloc(size);
    if (!rgb || fread(rgb, 1, size, stdin) != size) return fail(4, "the picture ended early", NULL);
    int r = cactus_describe(rgb, w, h, options, NULL, NULL, answer, ANSWER);
    free(rgb);
    if (r < 0) return fail(5, "reading failed", cactus_last_error());
    for (char* p = answer; *p; p++) if (*p == '\n' || *p == '\r') *p = ' ';
    printf("%s\n", answer);
    fflush(stdout);
  }
  return 0;
}
