/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.transformer.TransformationException.ERROR_CODE_MUXING_FAILED;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;
import static androidx.media3.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
import static java.lang.Math.min;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.FrameProcessor;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.metadata.mp4.SlowMotionData;
import com.google.common.collect.ImmutableList;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/* package */ final class TransformerInternal {

  public interface Listener {

    void onTransformationCompleted(TransformationResult transformationResult);

    void onTransformationError(TransformationException exception);
  }

  /**
   * Represents a reason for ending a transformation. May be one of {@link #END_REASON_COMPLETED},
   * {@link #END_REASON_CANCELLED} or {@link #END_REASON_ERROR}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({END_REASON_COMPLETED, END_REASON_CANCELLED, END_REASON_ERROR})
  private @interface EndReason {}
  /** The transformation completed successfully. */
  private static final int END_REASON_COMPLETED = 0;
  /** The transformation was cancelled. */
  private static final int END_REASON_CANCELLED = 1;
  /** An error occurred during the transformation. */
  private static final int END_REASON_ERROR = 2;

  // Internal messages.
  private static final int MSG_START = 0;
  private static final int MSG_REGISTER_SAMPLE_PIPELINE = 1;
  private static final int MSG_DEQUEUE_INPUT = 2;
  private static final int MSG_QUEUE_INPUT = 3;
  private static final int MSG_DRAIN_PIPELINES = 4;
  private static final int MSG_END = 5;

  private static final int DRAIN_PIPELINES_DELAY_MS = 50;

  private final Context context;
  private final TransformationRequest transformationRequest;
  private final ImmutableList<AudioProcessor> audioProcessors;
  private final ImmutableList<Effect> videoEffects;
  private final boolean forceSilentAudio;
  private final Codec.DecoderFactory decoderFactory;
  private final Codec.EncoderFactory encoderFactory;
  private final FrameProcessor.Factory frameProcessorFactory;
  private final Listener listener;
  private final DebugViewProvider debugViewProvider;
  private final Clock clock;
  private final HandlerWrapper applicationHandler;
  private final HandlerThread internalHandlerThread;
  private final HandlerWrapper internalHandler;
  private final ExoPlayerAssetLoader exoPlayerAssetLoader;
  private final List<SamplePipeline> samplePipelines;
  private final ConditionVariable dequeueBufferConditionVariable;
  private final MuxerWrapper muxerWrapper;
  private final ConditionVariable cancellingConditionVariable;

  @Nullable private DecoderInputBuffer pendingInputBuffer;
  private boolean isDrainingPipelines;
  private int silentSamplePipelineIndex;
  private @Transformer.ProgressState int progressState;
  private long progressPositionMs;
  private long durationUs;
  private @MonotonicNonNull RuntimeException cancelException;

  private volatile boolean released;

  public TransformerInternal(
      Context context,
      MediaItem mediaItem,
      @Nullable String outputPath,
      @Nullable ParcelFileDescriptor outputParcelFileDescriptor,
      TransformationRequest transformationRequest,
      ImmutableList<AudioProcessor> audioProcessors,
      ImmutableList<Effect> videoEffects,
      boolean removeAudio,
      boolean removeVideo,
      boolean forceSilentAudio,
      MediaSource.Factory mediaSourceFactory,
      Codec.DecoderFactory decoderFactory,
      Codec.EncoderFactory encoderFactory,
      FrameProcessor.Factory frameProcessorFactory,
      Muxer.Factory muxerFactory,
      Looper applicationLooper,
      Listener listener,
      FallbackListener fallbackListener,
      DebugViewProvider debugViewProvider,
      Clock clock) {
    this.context = context;
    this.transformationRequest = transformationRequest;
    this.audioProcessors = audioProcessors;
    this.videoEffects = videoEffects;
    this.forceSilentAudio = forceSilentAudio;
    this.decoderFactory = decoderFactory;
    this.encoderFactory = encoderFactory;
    this.frameProcessorFactory = frameProcessorFactory;
    this.listener = listener;
    this.debugViewProvider = debugViewProvider;
    this.clock = clock;
    applicationHandler = clock.createHandler(applicationLooper, /* callback= */ null);
    internalHandlerThread = new HandlerThread("Transformer:Internal");
    internalHandlerThread.start();
    Looper internalLooper = internalHandlerThread.getLooper();
    ComponentListener componentListener = new ComponentListener(mediaItem, fallbackListener);
    exoPlayerAssetLoader =
        new ExoPlayerAssetLoader(
            context,
            mediaItem,
            removeAudio,
            removeVideo,
            mediaSourceFactory,
            decoderFactory,
            internalLooper,
            componentListener,
            clock);
    samplePipelines = new ArrayList<>();
    silentSamplePipelineIndex = C.INDEX_UNSET;
    dequeueBufferConditionVariable = new ConditionVariable();
    muxerWrapper =
        new MuxerWrapper(
            outputPath,
            outputParcelFileDescriptor,
            muxerFactory,
            /* errorConsumer= */ componentListener::onTransformationError);
    cancellingConditionVariable = new ConditionVariable();
    progressState = PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
    // It's safe to use "this" because we don't send a message before exiting the constructor.
    @SuppressWarnings("nullness:methodref.receiver.bound")
    HandlerWrapper internalHandler =
        clock.createHandler(internalLooper, /* callback= */ this::handleMessage);
    this.internalHandler = internalHandler;
  }

  public void start() {
    internalHandler.sendEmptyMessage(MSG_START);
  }

  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      progressHolder.progress = min((int) (progressPositionMs * 100 / Util.usToMs(durationUs)), 99);
    }
    return progressState;
  }

  public void cancel() {
    if (released) {
      return;
    }
    internalHandler
        .obtainMessage(
            MSG_END, END_REASON_CANCELLED, /* unused */ 0, /* transformationException */ null)
        .sendToTarget();
    clock.onThreadBlocked();
    cancellingConditionVariable.blockUninterruptible();
    if (cancelException != null) {
      throw cancelException;
    }
  }

  private boolean handleMessage(Message msg) {
    // Handle end messages even if resources have been released to report release timeouts.
    if (released && msg.what != MSG_END) {
      return true;
    }
    try {
      switch (msg.what) {
        case MSG_START:
          startInternal();
          break;
        case MSG_REGISTER_SAMPLE_PIPELINE:
          registerSamplePipelineInternal((SamplePipeline) msg.obj);
          break;
        case MSG_DEQUEUE_INPUT:
          dequeueInputInternal(/* samplePipelineIndex= */ msg.arg1);
          break;
        case MSG_QUEUE_INPUT:
          samplePipelines.get(/* samplePipelineIndex= */ msg.arg1).queueInputBuffer();
          break;
        case MSG_DRAIN_PIPELINES:
          drainPipelinesInternal();
          break;
        case MSG_END:
          endInternal(
              /* endReason= */ msg.arg1,
              /* transformationException= */ (TransformationException) msg.obj);
          break;
        default:
          return false;
      }
    } catch (TransformationException e) {
      endInternal(END_REASON_ERROR, e);
    } catch (RuntimeException e) {
      endInternal(END_REASON_ERROR, TransformationException.createForUnexpected(e));
    }
    return true;
  }

  private void startInternal() {
    exoPlayerAssetLoader.start();
  }

  private void registerSamplePipelineInternal(SamplePipeline samplePipeline) {
    samplePipelines.add(samplePipeline);
    if (!isDrainingPipelines) {
      // Make sure pipelines are drained regularly to prevent them from getting stuck.
      internalHandler.sendEmptyMessageDelayed(MSG_DRAIN_PIPELINES, DRAIN_PIPELINES_DELAY_MS);
      isDrainingPipelines = true;
    }
  }

  private void dequeueInputInternal(int samplePipelineIndex) throws TransformationException {
    SamplePipeline samplePipeline = samplePipelines.get(samplePipelineIndex);
    // The sample pipeline is drained before dequeuing input to maximise the chances of having an
    // input buffer to dequeue.
    while (samplePipeline.processData()) {}
    pendingInputBuffer = samplePipeline.dequeueInputBuffer();
    dequeueBufferConditionVariable.open();

    if (forceSilentAudio) {
      while (samplePipelines.get(silentSamplePipelineIndex).processData()) {}
    }
  }

  private void drainPipelinesInternal() throws TransformationException {
    for (int i = 0; i < samplePipelines.size(); i++) {
      while (samplePipelines.get(i).processData()) {}
    }

    if (muxerWrapper.isEnded()) {
      internalHandler
          .obtainMessage(
              MSG_END, END_REASON_COMPLETED, /* unused */ 0, /* transformationException */ null)
          .sendToTarget();
    } else {
      internalHandler.sendEmptyMessageDelayed(MSG_DRAIN_PIPELINES, DRAIN_PIPELINES_DELAY_MS);
    }
  }

  private void endInternal(
      @EndReason int endReason, @Nullable TransformationException transformationException) {
    @Nullable TransformationResult transformationResult = null;
    boolean forCancellation = endReason == END_REASON_CANCELLED;
    @Nullable TransformationException releaseTransformationException = null;
    if (!released) {
      released = true;

      // Make sure there is no dequeue action waiting on the asset loader thread to avoid a
      // deadlock when releasing it.
      pendingInputBuffer = null;
      dequeueBufferConditionVariable.open();
      try {
        try {
          exoPlayerAssetLoader.release();
        } finally {
          try {
            for (int i = 0; i < samplePipelines.size(); i++) {
              samplePipelines.get(i).release();
            }
            if (endReason == END_REASON_COMPLETED) {
              transformationResult =
                  new TransformationResult.Builder()
                      .setDurationMs(checkNotNull(muxerWrapper).getDurationMs())
                      .setAverageAudioBitrate(
                          muxerWrapper.getTrackAverageBitrate(C.TRACK_TYPE_AUDIO))
                      .setAverageVideoBitrate(
                          muxerWrapper.getTrackAverageBitrate(C.TRACK_TYPE_VIDEO))
                      .setVideoFrameCount(muxerWrapper.getTrackSampleCount(C.TRACK_TYPE_VIDEO))
                      .setFileSizeBytes(muxerWrapper.getCurrentOutputSizeBytes())
                      .build();
            }
          } finally {
            muxerWrapper.release(forCancellation);
          }
        }
      } catch (Muxer.MuxerException e) {
        releaseTransformationException =
            TransformationException.createForMuxer(e, ERROR_CODE_MUXING_FAILED);
      } catch (RuntimeException e) {
        releaseTransformationException = TransformationException.createForUnexpected(e);
        // cancelException is not reported through a listener. It is thrown in cancel(), as this
        // method is blocking.
        cancelException = e;
      }
      // Quit thread lazily so that all events that got triggered when releasing the AssetLoader are
      // still delivered.
      internalHandler.post(internalHandlerThread::quitSafely);
    }

    if (!forCancellation) {
      TransformationException exception = transformationException;
      if (exception == null) {
        // We only report the exception caused by releasing the resources if there is no other
        // exception. It is more intuitive to call the error callback only once and reporting the
        // exception caused by releasing the resources can be confusing if it is a consequence of
        // the first exception.
        exception = releaseTransformationException;
      }

      if (exception != null) {
        listener.onTransformationError(exception);
      } else {
        listener.onTransformationCompleted(checkNotNull(transformationResult));
      }
    }

    cancellingConditionVariable.open();
  }

  private class ComponentListener
      implements ExoPlayerAssetLoader.Listener, SamplePipeline.Listener {

    private static final long MIN_DURATION_BETWEEN_PROGRESS_UPDATES_MS = 100;

    private final MediaItem mediaItem;
    private final FallbackListener fallbackListener;

    private int tracksAddedCount;
    private long lastProgressUpdateMs;
    private long lastProgressPositionMs;

    private volatile boolean trackRegistered;

    public ComponentListener(MediaItem mediaItem, FallbackListener fallbackListener) {
      this.mediaItem = mediaItem;
      this.fallbackListener = fallbackListener;
    }

    // ExoPlayerAssetLoader.Listener implementation.

    @Override
    public void onDurationUs(long durationUs) {
      applicationHandler.post(
          () -> {
            // Make progress permanently unavailable if the duration is unknown, so that it doesn't
            // jump to a high value at the end of the transformation if the duration is set once the
            // media is entirely loaded.
            progressState =
                durationUs <= 0 || durationUs == C.TIME_UNSET
                    ? PROGRESS_STATE_UNAVAILABLE
                    : PROGRESS_STATE_AVAILABLE;
            TransformerInternal.this.durationUs = durationUs;
          });
    }

    @Override
    public void onTrackRegistered() {
      trackRegistered = true;
      muxerWrapper.registerTrack();
      fallbackListener.registerTrack();

      if (forceSilentAudio) {
        muxerWrapper.registerTrack();
        fallbackListener.registerTrack();
      }
    }

    @Override
    public void onAllTracksRegistered() {
      if (!trackRegistered) {
        onError(new IllegalStateException("The output does not contain any tracks."));
      }
    }

    @Override
    public SamplePipeline.Input onTrackAdded(
        Format format, long streamStartPositionUs, long streamOffsetUs)
        throws TransformationException {
      SamplePipeline samplePipeline =
          getSamplePipeline(format, streamStartPositionUs, streamOffsetUs);
      internalHandler.obtainMessage(MSG_REGISTER_SAMPLE_PIPELINE, samplePipeline).sendToTarget();

      int samplePipelineIndex = tracksAddedCount;
      tracksAddedCount++;

      if (forceSilentAudio) {
        Format silentAudioFormat =
            new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(44100)
                .setChannelCount(2)
                .build();
        SamplePipeline audioSamplePipeline =
            getSamplePipeline(silentAudioFormat, streamStartPositionUs, streamOffsetUs);
        internalHandler
            .obtainMessage(MSG_REGISTER_SAMPLE_PIPELINE, audioSamplePipeline)
            .sendToTarget();
        silentSamplePipelineIndex = tracksAddedCount;
        tracksAddedCount++;
      }

      return new SamplePipelineInput(samplePipelineIndex, samplePipeline.expectsDecodedData());
    }

    @Override
    public void onError(Exception e) {
      TransformationException transformationException;
      if (e instanceof TransformationException) {
        transformationException = (TransformationException) e;
      } else if (e instanceof PlaybackException) {
        transformationException =
            TransformationException.createForPlaybackException((PlaybackException) e);
      } else {
        transformationException = TransformationException.createForUnexpected(e);
      }
      onTransformationError(transformationException);
    }

    // SamplePipeline.Listener implementation.

    @Override
    public void onInputBufferQueued(long positionUs) {
      long positionMs = Util.usToMs(positionUs);
      long elapsedTimeMs = clock.elapsedRealtime();
      if (elapsedTimeMs > lastProgressUpdateMs + MIN_DURATION_BETWEEN_PROGRESS_UPDATES_MS
          && positionMs > lastProgressPositionMs) {
        lastProgressUpdateMs = elapsedTimeMs;
        // Store positionMs in a variable to make sure the thread reads the latest value.
        lastProgressPositionMs = positionMs;
        applicationHandler.post(() -> progressPositionMs = positionMs);
      }
    }

    @Override
    public void onTransformationError(TransformationException transformationException) {
      internalHandler
          .obtainMessage(MSG_END, END_REASON_ERROR, /* unused */ 0, transformationException)
          .sendToTarget();
    }

    private SamplePipeline getSamplePipeline(
        Format inputFormat, long streamStartPositionUs, long streamOffsetUs)
        throws TransformationException {
      if (MimeTypes.isAudio(inputFormat.sampleMimeType) && shouldTranscodeAudio(inputFormat)) {
        return new AudioTranscodingSamplePipeline(
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            audioProcessors,
            forceSilentAudio ? durationUs : C.TIME_UNSET,
            encoderFactory,
            muxerWrapper,
            /* listener= */ this,
            fallbackListener);
      } else if (MimeTypes.isVideo(inputFormat.sampleMimeType)
          && shouldTranscodeVideo(inputFormat, streamStartPositionUs, streamOffsetUs)) {
        return new VideoTranscodingSamplePipeline(
            context,
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            videoEffects,
            frameProcessorFactory,
            decoderFactory,
            encoderFactory,
            muxerWrapper,
            /* listener= */ this,
            fallbackListener,
            debugViewProvider);
      } else {
        return new PassthroughSamplePipeline(
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            muxerWrapper,
            /* listener= */ this,
            fallbackListener);
      }
    }

    private boolean shouldTranscodeAudio(Format inputFormat) {
      if (encoderFactory.audioNeedsEncoding()) {
        return true;
      }
      if (transformationRequest.audioMimeType != null
          && !transformationRequest.audioMimeType.equals(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.audioMimeType == null
          && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.flattenForSlowMotion && isSlowMotion(inputFormat)) {
        return true;
      }
      if (!audioProcessors.isEmpty()) {
        return true;
      }
      if (forceSilentAudio) {
        return true;
      }

      return false;
    }

    private boolean isSlowMotion(Format format) {
      @Nullable Metadata metadata = format.metadata;
      if (metadata == null) {
        return false;
      }
      for (int i = 0; i < metadata.length(); i++) {
        if (metadata.get(i) instanceof SlowMotionData) {
          return true;
        }
      }
      return false;
    }

    private boolean shouldTranscodeVideo(
        Format inputFormat, long streamStartPositionUs, long streamOffsetUs) {
      if ((streamStartPositionUs - streamOffsetUs) != 0
          && !mediaItem.clippingConfiguration.startsAtKeyFrame) {
        return true;
      }
      if (encoderFactory.videoNeedsEncoding()) {
        return true;
      }
      if (transformationRequest.hdrMode != TransformationRequest.HDR_MODE_KEEP_HDR) {
        return true;
      }
      if (transformationRequest.videoMimeType != null
          && !transformationRequest.videoMimeType.equals(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.videoMimeType == null
          && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
        return true;
      }
      if (inputFormat.pixelWidthHeightRatio != 1f) {
        return true;
      }
      if (transformationRequest.rotationDegrees != 0f) {
        return true;
      }
      if (transformationRequest.scaleX != 1f) {
        return true;
      }
      if (transformationRequest.scaleY != 1f) {
        return true;
      }
      // The decoder rotates encoded frames for display by inputFormat.rotationDegrees.
      int decodedHeight =
          (inputFormat.rotationDegrees % 180 == 0) ? inputFormat.height : inputFormat.width;
      if (transformationRequest.outputHeight != C.LENGTH_UNSET
          && transformationRequest.outputHeight != decodedHeight) {
        return true;
      }
      if (!videoEffects.isEmpty()) {
        return true;
      }
      return false;
    }

    private class SamplePipelineInput implements SamplePipeline.Input {

      private final int samplePipelineIndex;
      private final boolean expectsDecodedData;

      public SamplePipelineInput(int samplePipelineIndex, boolean expectsDecodedData) {
        this.samplePipelineIndex = samplePipelineIndex;
        this.expectsDecodedData = expectsDecodedData;
      }

      @Override
      public boolean expectsDecodedData() {
        return expectsDecodedData;
      }

      @Nullable
      @Override
      public DecoderInputBuffer dequeueInputBuffer() {
        if (released) {
          // Make sure there is no dequeue action waiting on the asset loader thread when it is
          // being released to avoid a deadlock.
          return null;
        }
        // TODO(b/252537210): Reduce the number of thread hops (for example by adding a queue at the
        //  start of the sample pipelines). Having 2 thread hops per sample (one for dequeuing and
        //  one for queuing) makes transmuxing slower than it used to be.
        internalHandler
            .obtainMessage(MSG_DEQUEUE_INPUT, samplePipelineIndex, /* unused */ 0)
            .sendToTarget();
        clock.onThreadBlocked();
        dequeueBufferConditionVariable.blockUninterruptible();
        dequeueBufferConditionVariable.close();
        return pendingInputBuffer;
      }

      @Override
      public void queueInputBuffer() {
        internalHandler
            .obtainMessage(MSG_QUEUE_INPUT, samplePipelineIndex, /* unused */ 0)
            .sendToTarget();
      }
    }
  }
}