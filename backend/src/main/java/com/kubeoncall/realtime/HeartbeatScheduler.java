package com.kubeoncall.realtime;

/** Shared scheduler boundary used to emit lightweight SSE heartbeats. */
public interface HeartbeatScheduler {

    Registration schedule(Runnable heartbeat);

    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
