// Renders HDR test patches straight onto a surface with BT2020/PQ output.
//
// Why native: an app cannot produce PQ values with Canvas - that path is 8-bit sRGB. To put
// real PQ codes on the panel you need a 10-bit surface whose colour space is declared as
// BT2020/PQ, and that declaration only exists on the EGL surface (EGL_EXT_gl_colorspace_
// bt2020_pq). Once that is set up, the float the fragment shader writes is a PQ code, so any
// luminance can be requested at runtime and the patch geometry is exact pixels.

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <cstdio>
#include <cstring>

#define LOG_TAG "MoonbeamHdr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

EGLDisplay g_dpy = EGL_NO_DISPLAY;
EGLSurface g_surf = EGL_NO_SURFACE;
EGLContext g_ctx = EGL_NO_CONTEXT;
ANativeWindow *g_win = nullptr;
GLuint g_prog = 0;
GLint g_rectLoc = -1;
GLint g_nitsLoc = -1;
GLint g_nits2Loc = -1;
GLint g_cellLoc = -1;
int g_width = 0;
int g_height = 0;

const char *kVertex =
    "#version 300 es\n"
    "void main() {\n"
    "  vec2 p = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);\n"
    "  gl_Position = vec4(p, 0.0, 1.0);\n"
    "}\n";

// The written value is the PQ code itself: the surface declares BT2020/PQ, so no transfer
// function is applied on top of what we output.
const char *kFragment =
    "#version 300 es\n"
    "precision highp float;\n"
    "uniform vec4 uRect;\n"   // x0, y0, x1, y1 in 0..1, y down
    "uniform float uNits;\n"
    "uniform float uNits2;\n"  // second luminance, used by the grid
    "uniform float uCell;\n"   // grid pitch in pixels; 0 draws a single patch
    "out vec4 fragColor;\n"
    "float pq(float nits) {\n"
    "  float y = pow(clamp(nits, 0.0, 10000.0) / 10000.0, 0.1593017578125);\n"
    "  return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);\n"
    "}\n"
    "void main() {\n"
    "  if (uCell > 0.0) {\n"
    "    float checker = mod(floor(gl_FragCoord.x / uCell) + floor(gl_FragCoord.y / uCell), 2.0);\n"
    "    fragColor = vec4(vec3(pq(checker < 1.0 ? uNits : uNits2)), 1.0);\n"
    "    return;\n"
    "  }\n"
    "  vec2 uv = gl_FragCoord.xy / vec2(%d.0, %d.0);\n"
    "  if (uv.x < uRect.x || uv.x > uRect.z || uv.y < uRect.y || uv.y > uRect.w) discard;\n"
    "  fragColor = vec4(vec3(pq(uNits)), 1.0);\n"
    "}\n";

GLuint compile(GLenum type, const char *src) {
  GLuint shader = glCreateShader(type);
  glShaderSource(shader, 1, &src, nullptr);
  glCompileShader(shader);
  GLint ok = 0;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[512];
    glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
    LOGE("shader compile failed: %s", log);
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

bool hasExtension(const char *list, const char *name) {
  return list != nullptr && strstr(list, name) != nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_limelight_hdr_HdrCanvas_nativeInit(JNIEnv *env, jclass, jobject surface, jint width,
                                            jint height) {
  if (surface == nullptr || width <= 0 || height <= 0) {
    return JNI_FALSE;
  }
  g_width = width;
  g_height = height;

  g_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (g_dpy == EGL_NO_DISPLAY || !eglInitialize(g_dpy, nullptr, nullptr)) {
    LOGE("no EGL display");
    return JNI_FALSE;
  }

  const char *ext = eglQueryString(g_dpy, EGL_EXTENSIONS);
  bool pqSupported = hasExtension(ext, "EGL_EXT_gl_colorspace_bt2020_pq");
  LOGI("EGL PQ colorspace: %s", pqSupported ? "yes" : "no");

  const EGLint configAttribs[] = {
      EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
      EGL_RED_SIZE, 10,
      EGL_GREEN_SIZE, 10,
      EGL_BLUE_SIZE, 10,
      EGL_ALPHA_SIZE, 2,
      EGL_NONE};
  EGLConfig config;
  EGLint numConfigs = 0;
  if (!eglChooseConfig(g_dpy, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
    LOGE("no 10-bit EGL config");
    return JNI_FALSE;
  }

  const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  g_ctx = eglCreateContext(g_dpy, config, EGL_NO_CONTEXT, contextAttribs);
  if (g_ctx == EGL_NO_CONTEXT) {
    LOGE("no GLES3 context");
    return JNI_FALSE;
  }

  g_win = ANativeWindow_fromSurface(env, surface);
  g_surf = eglCreateWindowSurface(g_dpy, config, g_win, nullptr);
  if (g_surf == EGL_NO_SURFACE) {
    LOGE("no window surface");
    return JNI_FALSE;
  }

  if (pqSupported) {
    EGLBoolean applied = eglSurfaceAttrib(g_dpy, g_surf, EGL_GL_COLORSPACE_KHR,
                                          EGL_GL_COLORSPACE_BT2020_PQ_EXT);
    LOGI("PQ colorspace applied: %d", applied);
    if (!applied) {
      pqSupported = false;
    }
  }

  if (!eglMakeCurrent(g_dpy, g_surf, g_surf, g_ctx)) {
    LOGE("eglMakeCurrent failed");
    return JNI_FALSE;
  }

  char fragment[2048];
  snprintf(fragment, sizeof(fragment), kFragment, g_width, g_height);
  GLuint vs = compile(GL_VERTEX_SHADER, kVertex);
  GLuint fs = compile(GL_FRAGMENT_SHADER, fragment);
  if (vs == 0 || fs == 0) {
    return JNI_FALSE;
  }
  g_prog = glCreateProgram();
  glAttachShader(g_prog, vs);
  glAttachShader(g_prog, fs);
  glLinkProgram(g_prog);
  glDeleteShader(vs);
  glDeleteShader(fs);
  GLint linked = 0;
  glGetProgramiv(g_prog, GL_LINK_STATUS, &linked);
  if (!linked) {
    LOGE("program link failed");
    return JNI_FALSE;
  }
  g_rectLoc = glGetUniformLocation(g_prog, "uRect");
  g_nitsLoc = glGetUniformLocation(g_prog, "uNits");
  g_nits2Loc = glGetUniformLocation(g_prog, "uNits2");
  g_cellLoc = glGetUniformLocation(g_prog, "uCell");

  glViewport(0, 0, g_width, g_height);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
  eglSwapBuffers(g_dpy, g_surf);

  return pqSupported ? JNI_TRUE : JNI_FALSE;
}

// Draws one patch of `nits` inside the given pixel rectangle; the rest of the surface stays black.
JNIEXPORT void JNICALL
Java_com_limelight_hdr_HdrCanvas_nativeDraw(JNIEnv *, jclass, jint x, jint y, jint w, jint h,
                                            jfloat nits) {
  if (g_prog == 0) {
    return;
  }
  float x0 = (float) x / (float) g_width;
  float y0 = (float) y / (float) g_height;
  float x1 = (float) (x + w) / (float) g_width;
  float y1 = (float) (y + h) / (float) g_height;

  glViewport(0, 0, g_width, g_height);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
  glUseProgram(g_prog);
  // gl_FragCoord counts from the bottom, the rectangle comes from the top.
  glUniform4f(g_rectLoc, x0, 1.0f - y1, x1, 1.0f - y0);
  glUniform1f(g_nitsLoc, nits);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  eglSwapBuffers(g_dpy, g_surf);
}

// Full-screen checkerboard alternating two luminances. Every cell borders the other value,
// so the comparison is local everywhere instead of across a seam, and the total lit area is
// the whole screen - which is what the full-frame measurement is about.
JNIEXPORT void JNICALL
Java_com_limelight_hdr_HdrCanvas_nativeDrawGrid(JNIEnv *, jclass, jint cell, jfloat nitsA,
                                                jfloat nitsB) {
  if (g_prog == 0) {
    return;
  }
  glViewport(0, 0, g_width, g_height);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
  glUseProgram(g_prog);
  glUniform4f(g_rectLoc, 0.0f, 0.0f, 1.0f, 1.0f);
  glUniform1f(g_nitsLoc, nitsA);
  glUniform1f(g_nits2Loc, nitsB);
  glUniform1f(g_cellLoc, (float) (cell > 0 ? cell : 1));
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glUniform1f(g_cellLoc, 0.0f);
  eglSwapBuffers(g_dpy, g_surf);
}

// Two patches in one frame: clear once, draw both, swap once.
JNIEXPORT void JNICALL
Java_com_limelight_hdr_HdrCanvas_nativeDrawPair(JNIEnv *, jclass,
                                                jint x1, jint y1, jint w1, jint h1, jfloat n1,
                                                jint x2, jint y2, jint w2, jint h2, jfloat n2) {
  if (g_prog == 0) {
    return;
  }
  glViewport(0, 0, g_width, g_height);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
  glUseProgram(g_prog);

  const int xs[2] = {x1, x2};
  const int ys[2] = {y1, y2};
  const int ws[2] = {w1, w2};
  const int hs[2] = {h1, h2};
  const float ns[2] = {n1, n2};
  for (int i = 0; i < 2; i++) {
    float x0 = (float) xs[i] / (float) g_width;
    float y0 = (float) ys[i] / (float) g_height;
    float x1n = (float) (xs[i] + ws[i]) / (float) g_width;
    float y1n = (float) (ys[i] + hs[i]) / (float) g_height;
    glUniform4f(g_rectLoc, x0, 1.0f - y1n, x1n, 1.0f - y0);
    glUniform1f(g_nitsLoc, ns[i]);
    glUniform1f(g_cellLoc, 0.0f);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }
  eglSwapBuffers(g_dpy, g_surf);
}

// Evenly spaced vertical bands, one per entry of the array, spanning the given band.
JNIEXPORT void JNICALL
Java_com_limelight_hdr_HdrCanvas_nativeDrawLadder(JNIEnv *env, jclass, jint y, jint h,
                                                  jfloatArray nits) {
  if (g_prog == 0 || nits == nullptr) {
    return;
  }
  jsize count = env->GetArrayLength(nits);
  if (count <= 0) {
    return;
  }
  jfloat values[count];
  env->GetFloatArrayRegion(nits, 0, count, values);

  glViewport(0, 0, g_width, g_height);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
  glUseProgram(g_prog);

  for (jsize i = 0; i < count; i++) {
    int x0px = (int) ((long) g_width * i / count);
    int x1px = (int) ((long) g_width * (i + 1) / count);
    float x0 = (float) x0px / (float) g_width;
    float x1n = (float) x1px / (float) g_width;
    float y0 = (float) y / (float) g_height;
    float y1n = (float) (y + h) / (float) g_height;
    glUniform4f(g_rectLoc, x0, 1.0f - y1n, x1n, 1.0f - y0);
    glUniform1f(g_nitsLoc, values[i]);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  }
  eglSwapBuffers(g_dpy, g_surf);
}

JNIEXPORT void JNICALL
Java_com_limelight_hdr_HdrCanvas_nativeRelease(JNIEnv *, jclass) {
  if (g_dpy != EGL_NO_DISPLAY) {
    eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (g_surf != EGL_NO_SURFACE) {
      eglDestroySurface(g_dpy, g_surf);
      g_surf = EGL_NO_SURFACE;
    }
    if (g_ctx != EGL_NO_CONTEXT) {
      eglDestroyContext(g_dpy, g_ctx);
      g_ctx = EGL_NO_CONTEXT;
    }
    eglTerminate(g_dpy);
    g_dpy = EGL_NO_DISPLAY;
  }
  if (g_win != nullptr) {
    ANativeWindow_release(g_win);
    g_win = nullptr;
  }
  g_prog = 0;
}

}  // extern "C"
