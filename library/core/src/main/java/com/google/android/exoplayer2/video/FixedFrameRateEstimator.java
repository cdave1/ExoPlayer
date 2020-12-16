/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.google.android.exoplayer2.video;

import androidx.annotation.VisibleForTesting;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import java.util.Arrays;

/**
 * Attempts to detect and refine a fixed frame rate estimate based on frame presentation timestamps.
 */
/* package */ final class FixedFrameRateEstimator {

  /**
   * The number of consecutive matching frame durations for the tracker to be considered in sync.
   */
  @VisibleForTesting static final int CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC = 10;
  /**
   * The maximum amount frame durations can differ for them to be considered matching, in
   * nanoseconds.
   *
   * <p>This constant is set to 1ms to account for container formats that only represent frame
   * presentation timestamps to the nearest millisecond. In such cases, frame durations need to
   * switch between values that are 1ms apart to achieve common fixed frame rates (e.g., 30fps
   * content will need frames that are 33ms and 34ms).
   */
  @VisibleForTesting static final long MAX_MATCHING_FRAME_DIFFERENCE_NS = 1_000_000;

  private Matcher currentMatcher;
  private Matcher candidateMatcher;
  private boolean candidateMatcherActive;
  private boolean switchToCandidateMatcherWhenSynced;
  private float formatFrameRate;
  private long lastFramePresentationTimeNs;

  public FixedFrameRateEstimator() {
    currentMatcher = new Matcher();
    candidateMatcher = new Matcher();
    formatFrameRate = Format.NO_VALUE;
    lastFramePresentationTimeNs = C.TIME_UNSET;
  }

  /** Resets the estimator. */
  public void reset() {
    currentMatcher.reset();
    candidateMatcher.reset();
    candidateMatcherActive = false;
    formatFrameRate = Format.NO_VALUE;
    lastFramePresentationTimeNs = C.TIME_UNSET;
  }

  /**
   * Called when the renderer's output format changes.
   *
   * @param formatFrameRate The format's frame rate, or {@link Format#NO_VALUE} if unknown.
   */
  public void onFormatChanged(float formatFrameRate) {
    // The format frame rate is only used to determine to what extent the estimator should be reset.
    // Frame rate estimates are always calculated directly from frame presentation timestamps.
    if (this.formatFrameRate != formatFrameRate) {
      reset();
    } else {
      // Keep the current matcher, but prefer to switch to a new matcher once synced even if the
      // current one does not lose sync. This avoids an issue where the current matcher would
      // continue to be used if a frame rate change has occurred that's too small to trigger sync
      // loss (e.g., a change from 30fps to 29.97fps) and which is not represented in the format
      // frame rates (e.g., because they're unset or only have integer precision).
      switchToCandidateMatcherWhenSynced = true;
    }
    this.formatFrameRate = formatFrameRate;
  }

  /**
   * Called with each frame presentation timestamp.
   *
   * @param framePresentationTimeNs The frame presentation timestamp, in nanoseconds.
   */
  public void onNextFrame(long framePresentationTimeNs) {
    currentMatcher.onNextFrame(framePresentationTimeNs);
    if (currentMatcher.isSynced() && !switchToCandidateMatcherWhenSynced) {
      candidateMatcherActive = false;
    } else if (lastFramePresentationTimeNs != C.TIME_UNSET) {
      if (!candidateMatcherActive || candidateMatcher.isLastFrameOutlier()) {
        // Reset the candidate with the last and current frame presentation timestamps, so that it
        // will try and match against the duration of the previous frame.
        candidateMatcher.reset();
        candidateMatcher.onNextFrame(lastFramePresentationTimeNs);
      }
      candidateMatcherActive = true;
      candidateMatcher.onNextFrame(framePresentationTimeNs);
    }
    if (candidateMatcherActive && candidateMatcher.isSynced()) {
      // The candidate matcher should be promoted to be the current matcher. The current matcher
      // can be re-used as the next candidate matcher.
      Matcher previousMatcher = currentMatcher;
      currentMatcher = candidateMatcher;
      candidateMatcher = previousMatcher;
      candidateMatcherActive = false;
      switchToCandidateMatcherWhenSynced = false;
    }
    lastFramePresentationTimeNs = framePresentationTimeNs;
  }

  /** Returns whether the estimator has detected a fixed frame rate. */
  public boolean isSynced() {
    return currentMatcher.isSynced();
  }

  /**
   * The currently detected fixed frame duration estimate in nanoseconds, or {@link C#TIME_UNSET} if
   * {@link #isSynced()} is {@code false}. Whilst synced, the estimate is refined each time {@link
   * #onNextFrame} is called with a new frame presentation timestamp.
   */
  public long getFrameDurationNs() {
    return isSynced() ? currentMatcher.getFrameDurationNs() : C.TIME_UNSET;
  }

  /**
   * The currently detected fixed frame rate estimate, or {@link Format#NO_VALUE} if {@link
   * #isSynced()} is {@code false}. Whilst synced, the estimate is refined each time {@link
   * #onNextFrame} is called with a new frame presentation timestamp.
   */
  public double getFrameRate() {
    return isSynced()
        ? (double) C.NANOS_PER_SECOND / currentMatcher.getFrameDurationNs()
        : Format.NO_VALUE;
  }

  /** Tries to match frame durations against the duration of the first frame it receives. */
  private static final class Matcher {

    private long firstFramePresentationTimeNs;
    private long firstFrameDurationNs;
    private long lastFramePresentationTimeNs;
    private long frameCount;

    /** The total number of frames that have matched the frame duration being tracked. */
    private long matchingFrameCount;
    /** The sum of the frame durations of all matching frames. */
    private long matchingFrameDurationSumNs;
    /** Cyclic buffer of flags indicating whether the most recent frame durations were outliers. */
    private final boolean[] recentFrameOutlierFlags;
    /**
     * The number of recent frame durations that were outliers. Equal to the number of {@code true}
     * values in {@link #recentFrameOutlierFlags}.
     */
    private int recentFrameOutlierCount;

    public Matcher() {
      recentFrameOutlierFlags = new boolean[CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC];
    }

    public void reset() {
      frameCount = 0;
      matchingFrameCount = 0;
      matchingFrameDurationSumNs = 0;
      recentFrameOutlierCount = 0;
      Arrays.fill(recentFrameOutlierFlags, false);
    }

    public boolean isSynced() {
      return frameCount > CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC
          && recentFrameOutlierCount == 0;
    }

    public boolean isLastFrameOutlier() {
      if (frameCount == 0) {
        return false;
      }
      return recentFrameOutlierFlags[getRecentFrameOutlierIndex(frameCount - 1)];
    }

    public long getFrameDurationNs() {
      return matchingFrameCount == 0 ? 0 : (matchingFrameDurationSumNs / matchingFrameCount);
    }

    public void onNextFrame(long framePresentationTimeNs) {
      if (frameCount == 0) {
        firstFramePresentationTimeNs = framePresentationTimeNs;
      } else if (frameCount == 1) {
        // This is the frame duration that the tracker will match against.
        firstFrameDurationNs = framePresentationTimeNs - firstFramePresentationTimeNs;
        matchingFrameDurationSumNs = firstFrameDurationNs;
        matchingFrameCount = 1;
      } else {
        long lastFrameDurationNs = framePresentationTimeNs - lastFramePresentationTimeNs;
        int recentFrameOutlierIndex = getRecentFrameOutlierIndex(frameCount);
        if (Math.abs(lastFrameDurationNs - firstFrameDurationNs)
            <= MAX_MATCHING_FRAME_DIFFERENCE_NS) {
          matchingFrameCount++;
          matchingFrameDurationSumNs += lastFrameDurationNs;
          if (recentFrameOutlierFlags[recentFrameOutlierIndex]) {
            recentFrameOutlierFlags[recentFrameOutlierIndex] = false;
            recentFrameOutlierCount--;
          }
        } else {
          if (!recentFrameOutlierFlags[recentFrameOutlierIndex]) {
            recentFrameOutlierFlags[recentFrameOutlierIndex] = true;
            recentFrameOutlierCount++;
          }
        }
      }

      frameCount++;
      lastFramePresentationTimeNs = framePresentationTimeNs;
    }

    private static int getRecentFrameOutlierIndex(long frameCount) {
      return (int) (frameCount % CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC);
    }
  }
}
