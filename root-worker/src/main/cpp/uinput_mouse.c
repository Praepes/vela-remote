#include <jni.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

static void throw_io(JNIEnv *env, const char *operation) {
    int error = errno;
    char message[192];
    snprintf(message, sizeof(message), "%s: %s", operation, strerror(error));
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static int write_all(int fd, const void *data, size_t size) {
    const char *next = data;
    while (size > 0) {
        ssize_t count = write(fd, next, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { if (count == 0) errno = EIO; return -1; }
        next += count;
        size -= (size_t)count;
    }
    return 0;
}

JNIEXPORT jint JNICALL Java_dev_controlpad_worker_UinputMouse_openNative(JNIEnv *env, jobject self) {
    (void)self;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { throw_io(env, "open /dev/uinput"); return -1; }
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_LEFT) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_REL) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_X) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_Y) < 0 ||
        ioctl(fd, UI_SET_RELBIT, REL_WHEEL) < 0) goto failure;
    // The legacy write interface works on kernels predating UI_DEV_SETUP (including 3.10).
    struct uinput_user_dev device;
    memset(&device, 0, sizeof(device));
    snprintf(device.name, sizeof(device.name), "Vela Remote Mouse");
    device.id.bustype = BUS_VIRTUAL;
    device.id.vendor = 0x1209;
    device.id.product = 0x0001;
    device.id.version = 1;
    if (write_all(fd, &device, sizeof(device)) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) goto failure;
    return fd;
failure: {
    int error = errno;
    close(fd);
    errno = error;
    throw_io(env, "create uinput mouse");
    return -1;
}
}

static void event(struct input_event *events, int *count, unsigned short type, unsigned short code, int value) {
    struct input_event *next = &events[(*count)++];
    memset(next, 0, sizeof(*next));
    next->type = type;
    next->code = code;
    next->value = value;
}

JNIEXPORT void JNICALL Java_dev_controlpad_worker_UinputMouse_reportNative(
        JNIEnv *env, jobject self, jint fd, jint buttons, jint changed, jint x, jint y, jint wheel) {
    (void)self;
    struct input_event events[7];
    int count = 0;
    const unsigned short keys[] = { BTN_LEFT, BTN_RIGHT, BTN_MIDDLE };
    for (int i = 0; i < 3; ++i) {
        if (changed & (1 << i)) event(events, &count, EV_KEY, keys[i], (buttons >> i) & 1);
    }
    if (x != 0) event(events, &count, EV_REL, REL_X, x);
    if (y != 0) event(events, &count, EV_REL, REL_Y, y);
    if (wheel != 0) event(events, &count, EV_REL, REL_WHEEL, wheel);
    if (count == 0) return;
    event(events, &count, EV_SYN, SYN_REPORT, 0);
    if (write_all(fd, events, (size_t)count * sizeof(events[0])) < 0) throw_io(env, "write uinput report");
}

JNIEXPORT void JNICALL Java_dev_controlpad_worker_UinputMouse_closeNative(JNIEnv *env, jobject self, jint fd) {
    (void)env; (void)self;
    struct input_event events[4];
    int count = 0;
    event(events, &count, EV_KEY, BTN_LEFT, 0);
    event(events, &count, EV_KEY, BTN_RIGHT, 0);
    event(events, &count, EV_KEY, BTN_MIDDLE, 0);
    event(events, &count, EV_SYN, SYN_REPORT, 0);
    write_all(fd, events, sizeof(events));
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}
