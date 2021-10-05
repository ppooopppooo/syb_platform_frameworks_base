/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.unfold;

import android.annotation.FloatRange;

import java.util.concurrent.Executor;

/**
 * Wrapper interface for unfold transition progress provider for the Shell
 * @see com.android.systemui.unfold.UnfoldTransitionProgressProvider
 */
public interface ShellUnfoldProgressProvider {

    /**
     * Adds a transition listener
     */
    void addListener(Executor executor, UnfoldListener listener);

    /**
     * Listener for receiving unfold updates
     */
    interface UnfoldListener {
        default void onStateChangeStarted() {}

        default void onStateChangeProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {}

        default void onStateChangeFinished() {}
    }
}
