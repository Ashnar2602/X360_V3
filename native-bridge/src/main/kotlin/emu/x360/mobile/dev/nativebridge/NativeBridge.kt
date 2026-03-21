package emu.x360.mobile.dev.nativebridge

object NativeBridge {
    init {
        System.loadLibrary("nativebridge")
    }

    external fun healthCheck(): String

    external fun bootstrapStub(runtimeRoot: String): String

    external fun describeSurfaceHookPlaceholder(rootfsTmpPath: String): String

    external fun inspectKgslProperties(): String

    external fun adoptFdForExec(rawFd: Int, minimumFd: Int): Int

    external fun remapFdToStdinForExec(fd: Int): Int

    external fun restoreStdinAfterExec(savedFd: Int): Boolean

    external fun closeFd(fd: Int): Boolean
}
