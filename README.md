# COMP3011 Assignment 2 - Java Local Project

\## Concurrency Architecture



\### Thread ownership



\- \*\*JavaFX Application Thread:\*\* Handles UI updates and publishes playback state.

\- \*\*Decoder worker:\*\* Owns sequential FFmpeg frame decoding, protected by the decoder lock.

\- \*\*Processing pool:\*\* Applies frame effects concurrently using worker-local `FrameProcessor` instances.

\- \*\*Playback coordinator:\*\* Tracks frame ordering, playback timing, and in-flight work.



\### Frame processing



Frames are decoded sequentially and then submitted to a bounded processing pool. Each processing worker has its own effect instances, preventing mutable effect state from being shared between workers.



Processed frames are stored by sequence number and published in the correct order. Playback-generation checks discard results belonging to an earlier playback session or seek.



\### Backpressure and correctness



The number of in-flight frames is bounded to prevent unlimited queued work. Frame reservations are released on success, failure, and stale-result paths to avoid permanently consuming capacity.



Decoder access is protected by a lock, and shared playback state uses appropriate synchronisation or atomic variables. JavaFX-facing updates are dispatched to the JavaFX application thread.



\### Validation



Run `mvn verify` to execute the project's automated verification and performance comparison. The benchmark compares the modified player with the original implementation and helps detect regressions in playback performance and frame accuracy.

