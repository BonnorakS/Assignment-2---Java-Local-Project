/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <Bonnorak SUN - a3150511>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

//NEW: new imports
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
	// NEW: Bounded pool for frame-processing jobs.
	// Never run rejected processing jobs on the submitting thread.
	private static final int FRAME_WORKER_COUNT = 4;
	private final ThreadPoolExecutor processingPool = new ThreadPoolExecutor(
			FRAME_WORKER_COUNT, FRAME_WORKER_COUNT, 0L, TimeUnit.MICROSECONDS,
			new ArrayBlockingQueue<>(FRAME_WORKER_COUNT * 2), task -> {
				Thread thread = new Thread(task, "frame-processor");
				thread.setDaemon(true);
				return thread;
			},
			new ThreadPoolExecutor.AbortPolicy()
			);
	//NEW: Give each processing threat its own processor list 
	// This prevents multiple threads from sharing effect objects that may contain mutable state.
	private final ThreadLocal<List<FrameProcessor>> workerProcessors = new ThreadLocal<>();
	
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;
    // CHANGE: Audio samples may be queued by the frame worker and consumed by JavaFX.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
    
    
    // NEW: One independent processor chain per processing worker.
    private final List<List<FrameProcessor>> workerProcessorChains = new ArrayList<>();
    // NEW: Perform expensive decoding, conversion and effects in the background.
    private final ExecutorService frameWorker = Executors.newSingleThreadExecutor(task -> {
    	Thread thread = new Thread(task, "video-frame-worker");
    	thread.setDaemon(true);
    	return thread;
    });
    //NEW: FFmpeg and its converter must not be accessed concurrently.
    private final Object decoderLock = new Object();

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
    //NEW: Prevent multiple frame preparation jobs from being scheduled.
    private volatile boolean framePreparationInProgress;
    //NEW: Prevent another frame from being decoded while an earlier frame
    // is still being processed. This provides backpressure to the worker pool.
    private volatile boolean frameProcessingInProgress = false;
    //NEW: Defer playback completion until all submitted frames have been handled.
    private volatile boolean endOfStream;
    //NEW: Reject completed frames from earlier playback/seek operations.
    private volatile long playbackGeneration;
    private volatile boolean playbackOpen; // True when playback is happening.
    private volatile boolean audioOutputEnabled;
    private volatile boolean audioAvailable;
    private boolean pauseRequested;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
         // CHANGE: Add List<FrameProcessor> frameProcessors as the second parameter.
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
     // CHANGE: Copy the configured processors instead of starting with an empty list.
        this.frameProcessors = new ArrayList<>(frameProcessors);
//        frameProcessors.add(new FrameBleeder());
//        frameProcessors.add(new FrameScratcher());
//        frameProcessors.add(new FrameDuster());
//        frameProcessors.add(new FramePepperer());
//        frameProcessors.add(new FrameBlackAndWhiter());
//        frameProcessors.add(new FrameYellower());
//        frameProcessors.add(new FrameVignetter());
//        frameProcessors.add(new FrameFlickerer());
//        frameProcessors.add(new FrameJitterer());
//        frameProcessors.add(new FrameNumberer());
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        // NEW: Release the background worker when the application exists.
        //NEW:  Shut down both background executors.
        frameWorker.shutdown();
        processingPool.shutdown();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    // CHANGE: Queue seeking on the frame worker so the JavaFX thread
    // never waits for decoderLock while an expensive frame is processing.
    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        // CHANGE: Invalidate any frame currently being prepared.
        long seekGeneration = ++playbackGeneration;
        preparedFrame = null;
        relativeSeekBaseUs = logicalTimestampUs;

        try {
            frameWorker.execute(() -> {
                Exception failure = null;

                try {
                    synchronized (decoderLock) {
                        // CHANGE: Ignore this seek if a newer seek or
                        // playBack session has superseded it.
                        if (!playbackOpen || seekGeneration != playbackGeneration) {
                            return;
                        }

                        grabber.setTimestamp(grabTimestampUs);
                    }
                } catch (Exception e) {
                    failure = e;
                }

                final Exception seekFailure = failure;

                Platform.runLater(() -> {
                    if (!playbackOpen || seekGeneration != playbackGeneration) {
                        return;
                    }

                    if (seekFailure != null) {
                        handlePlaybackError(seekFailure);
                        return;
                    }

                    // CHANGE: Reset playBack timing on the JavaFX thread
                    // after the decoder has completed the seek.
                    resetPlaybackClock(logicalTimestampUs);
                    framePreparationInProgress = false;
                    prepareNextFrame();
                    flushAudioOutput();
                    notifyPlaybackStateChanged();
                });
            });
        } catch (RejectedExecutionException e) {
            // The worker may already be shutting down.
            // Do not block the JavaFX thread trying to recover.
        }
    }


    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();
        //NEW
        endOfStream = false;
        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            //NEW
            endOfStream = false;
            playbackTimer.start();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();
        
        // CHANGE: Capture dimensions before dispatching the UI callback
        int width = grabber.getImageWidth();
        int height = grabber.getImageHeight();
        Platform.runLater(() -> notifyVideoSizeChanged(width, height));
        
        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
        
        for (PreparedFrame frame : completedFrames.values()) {
        	frame.reservation().release(inFlightFrames);
        }
        completedFrames.clear();
        nextFrameToDisplay = nextFrameSequence.get();
        preparedFrame = null;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }


  //CHANGE: A displayed/prepared frame must not prevent buffering future frames.
 private void prepareNextFrame() {
     if (!playbackOpen || pauseRequested || preparedFrame != null || framePreparationInProgress || inFlightFrames.get() >= MAX_IN_FLIGHT_FRAMES) {
         return;
     }

     framePreparationInProgress = true;
     final long taskGeneration = playbackGeneration;
     final FrameReservation reservation = new FrameReservation();
     //NEW: Reserve capacity before scheduling work.
     inFlightFrames.incrementAndGet();
     try {
         frameWorker.execute(() -> {
             DecodedFrame decodedFrame = null;
             Exception decodeFailure = null;

             try {
                 synchronized (decoderLock) {
                     if (playbackOpen && taskGeneration == playbackGeneration) {
                         //CHANGE: Keep the decoded result in the outer variable.
                         decodedFrame = readNextVideoFrame(taskGeneration);
                     }
                 }
             } catch (Exception e) {
            	 decodeFailure = e;
             }

             final DecodedFrame decoded = decodedFrame;
             final Exception failure  = decodeFailure;

             Platform.runLater(() -> {
                 //CHANGE: Release the decoder flag when decoding finishes.
                 framePreparationInProgress = false;
        
                 // Discard work belonging to an earlier playback or seek.
                 if (taskGeneration != playbackGeneration || !playbackOpen) {
                	 reservation.release(inFlightFrames);
                     return;
                 }

                 if (failure != null) {
                	 reservation.release(inFlightFrames);
                     handlePlaybackError(failure);
                     return;
                 }

                 if (decoded == null) {
                	 reservation.release(inFlightFrames);
                	 endOfStream = true;
                     return;
                 }
   
                 //NEW:  Allow other frames to be decoded while this one is processed.
                 try {
                     processingPool.execute(() -> {
                         PreparedFrame result = null;
                         Exception processingFailure = null;

                         try {
                             //NEW: Use this worker's own effect instances.
                             List<FrameProcessor> processors =
                                     getWorkerProcessors(decoded.videoInfo());

                             processFrame(
                                     decoded.frame(),
                                     decoded.info(),
                                     processors);

                             //NEW: Convert the processed frame to a JavaFX image.
                             JavaFXFrameConverter workerConverter =
                                     new JavaFXFrameConverter();

                             try {
                                 Image image = workerConverter.convert(decoded.frame());

                                 if (image == null) {
                                     throw new IllegalStateException(
                                             "Frame conversion returned null.");
                                 }

                                 result = new PreparedFrame(
                                         image,
                                         decoded.info().frameNumber(),
                                         decoded.timestampUs(),
                                         decoded.logicalTimestampUs(),
                                         decoded.targetTimeNs(),
                                         System.nanoTime(),
                                         decoded.sequence(),
                                         decoded.generation(),
                                         reservation);
                             } finally {
                                 workerConverter.close();
                             }
                         } catch (Exception e) {
                             processingFailure = e;
                         }

                         final PreparedFrame readyFrame = result;
                         final Exception readyFailure = processingFailure;

                         Platform.runLater(() -> {
                             if (!playbackOpen
                                     || decoded.generation() != playbackGeneration) {
                                 reservation.release(inFlightFrames);
                                 return;
                             }

                             if (readyFailure != null) {
                                 reservation.release(inFlightFrames);
                                 handlePlaybackError(readyFailure);
                                 return;
                             }

                             //NEW: Do not display here; store by sequence.
                             completedFrames.put(
                                     readyFrame.sequence(),
                                     readyFrame);
                         });
                     });
                 } catch (RejectedExecutionException e) {
                     reservation.release(inFlightFrames);
                     handlePlaybackError(e);
                 }
             });
         });
     } catch (RejectedExecutionException e) {
         framePreparationInProgress = false;
         reservation.release(inFlightFrames);
         handlePlaybackError(e);
     }
 }
    
   // CHANGE: Decode frames and capture metadata without running effects.
   // Processing is moved to processingPool so multiple frames can be processed concurrently.
    private DecodedFrame readNextVideoFrame(long taskGeneration) throws Exception {
        while (playbackOpen && taskGeneration == playbackGeneration) {
            Frame frame = grabFrame();
            if (frame == null) {
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            // Preserve audio samples before handing the image to another thread.
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            InfoVideo videoInfo = new InfoVideo(
            		mediaName(videoFile),
                    totalVideoFrames,
                    frame.imageWidth,
                    frame.imageHeight,
                    frame.imageDepth,
                    frame.imageChannels,
                    frame.imageStride,
                    frameRate,
                    intFrameRate,
                    videoFrameDurationUs,
                    grabber.getPixelFormat());

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
            
            // NEW: Clone the frame so processing workers don't share decoder buffers.
            Frame frameCopy = frame.clone();
            return new DecodedFrame(
                    frameCopy,
                    info,
                    videoInfo,
                    nextFrameSequence.getAndIncrement(),
                    taskGeneration,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs);
        }

        return null;
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1) : Keep audio flowing.
        writeDueAudio(now);

        // Task (2) : Display a frame when its scheduled time arrives.
        PreparedFrame next = completedFrames.get(nextFrameToDisplay);
        if (next != null && next.targetTimeNs() <= now) {
        	completedFrames.remove(nextFrameToDisplay);
        	preparedFrame = next;
        	nextFrameToDisplay++;
            // We have a prepared frame ready to go and it is due (or just past due!) so get it up on screen ASAP!
            displayPreparedFrame(now);
            //NEW: Release capacity only after the frame has been displayed.
            next.reservation().release(inFlightFrames);
        }

        // Task (3). We'll try and be nice to the GUI event loop here by queueing the prepareNextFrame call rather than
        // hogging the thread and doing it here, hence the tricky callback and use of prepareNextFrameQueued. Feel free
        // to replace this whole block with simple linear control flow logic
        // such as: if (!pauseRequested && preparedFrame == null) prepareNextFrame();
        // to compare.
        //CHANGE: Stop scheduling only when paused, at EOF, or at the buffer limit.
        if (!pauseRequested && !endOfStream && !prepareNextFrameQueued && !framePreparationInProgress && inFlightFrames.get() < MAX_IN_FLIGHT_FRAMES) {
            prepareNextFrameQueued = true;
            Platform.runLater(() -> {
                prepareNextFrameQueued = false;

                if (!pauseRequested) {
                    prepareNextFrame();
                }
            });
        }
        
        //NEW: Finish only after decoding has ended and every submitted frame is handled.
        if (endOfStream && !framePreparationInProgress && inFlightFrames.get() == 0 && completedFrames.isEmpty() && preparedFrame == null) {
        	finishPlayback();
        	return;
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }


private void closePlaybackResources() {
    synchronized (decoderLock) {
        playbackTimer.stop();
        playbackOpen = false;

        // CHANGE: Invalidate any frame task from the previous session.
        playbackGeneration++;

        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
            	// The resource is being closed; there is no useful recovery action.
            }

            try {
                grabber.close();
            } catch (Exception e) {
            	// The resource is being closed; there is no useful recovery action.
            }

            grabber = null;
        }
    }
}

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }
    
    //CHANGE: Process a frame using the specified worker-local processor chain.
    private void processFrame(Frame frame, InfoFrame info, List<FrameProcessor> processors) throws Exception {
        for (FrameProcessor processor : processors) {
            if(processor.videoInfo == null) {
            	throw new IllegalStateException("FrameProcessor was not initialised: " +
            processor.getClass().getSimpleName());
            }
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }
    
    // NEW: Create an independent processor chain for a worker.
    private List<FrameProcessor> createWorkerProcessors(InfoVideo videoInfo) throws Exception{
    	List<FrameProcessor> workerProcessors = new ArrayList<>();
    	for (FrameProcessor processor : frameProcessors) {
    		FrameProcessor copy = processor.copyForWorker();
    		copy.initialise(videoInfo);
    		workerProcessors.add(copy);
    	}
    	return workerProcessors;
    }
    
    //NEW: Initialise one processor chain per worker for the current video.
    private void initialiseWorkerProcessorChains(InfoVideo videoInfo) throws Exception{
    	workerProcessorChains.clear();
    	for (int i = 0; i < FRAME_WORKER_COUNT; i++) {
    		workerProcessorChains.add(createWorkerProcessors(videoInfo));
    	}
    }
    
    //NEW: Initialise worker-specific effects before processing frames.
    //  Each worker receives independent effect instances with video metadata initialised.
    private List<FrameProcessor> getWorkerProcessors(InfoVideo videoInfo) throws Exception {
    	List<FrameProcessor> processors = workerProcessors.get();
    	if (processors == null) {
    		processors = new ArrayList<>();
    		
    		for (FrameProcessor original : frameProcessors) {
    			//NEW: Initialise the copy before calling process().
    			FrameProcessor copy = original.copyForWorker();
    			copy.initialise(videoInfo);
    			processors.add(copy);
    		}
    		workerProcessors.set(processors);
    	}
    	return processors;
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }
    
    // NEW: Copy am image frame so workers do not share the decoder's pixel buffer.
    private Frame copyImageFrame(Frame source) {
    	Frame copy = source.clone();
    	return copy;
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs,
            long sequence,
            long generation,
            FrameReservation reservation) {
    }
    
    //NEW: Store a decoded frame and its timing metadata before effect processing.
    // This lets the decoder pass frames to processing workers without sharing the grabber.
    private record DecodedFrame(Frame frame, InfoFrame info, InfoVideo videoInfo, long sequence, long generation,
    		long timestampUs, long logicalTimestampUs, long targetTimeNs) {
    	
    }
    
	//NEW: Frames completed by workers, indexed by submission sequence.
	private final Map<Long, PreparedFrame> completedFrames = new TreeMap<>();
	
	//NEW: Sequence numbers preserve display order when workers finish out of order.
	private final AtomicLong nextFrameSequence = new AtomicLong(0);
	private long nextFrameToDisplay = 0;
	//NEW: Limit frames being decoded or processed at the same time.
	private static final int MAX_IN_FLIGHT_FRAMES = Math.max(2, FRAME_WORKER_COUNT * 2);
	//NEW: Counts frames from decode submission until display or discard.
	private final AtomicInteger inFlightFrames = new AtomicInteger(0);
	//NEW: Protects each frame reservation from being released twice.
	private static final class FrameReservation {
	    private final AtomicInteger released = new AtomicInteger(0);

	    boolean release(AtomicInteger counter) {
	        if (released.compareAndSet(0, 1)) {
	            counter.decrementAndGet();
	            return true;
	        }
	        return false;
	    }
	}
    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
