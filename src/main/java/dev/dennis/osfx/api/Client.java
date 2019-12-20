package dev.dennis.osfx.api;

import dev.dennis.osfx.Callbacks;

public interface Client extends GameEngine {
    Callbacks getCallbacks();

    void setCallbacks(Callbacks callbacks);

    BufferProvider getBufferProvider();

    int getCameraPitch();

    int getCameraYaw();
}
