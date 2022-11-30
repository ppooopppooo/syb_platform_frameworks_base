/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.controls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeControlsSettingsRepository : ControlsSettingsRepository {
    private val _canShowControlsInLockscreen = MutableStateFlow(false)
    override val canShowControlsInLockscreen = _canShowControlsInLockscreen.asStateFlow()
    private val _allowActionOnTrivialControlsInLockscreen = MutableStateFlow(false)
    override val allowActionOnTrivialControlsInLockscreen =
        _allowActionOnTrivialControlsInLockscreen.asStateFlow()

    fun setCanShowControlsInLockscreen(value: Boolean) {
        _canShowControlsInLockscreen.value = value
    }

    fun setAllowActionOnTrivialControlsInLockscreen(value: Boolean) {
        _allowActionOnTrivialControlsInLockscreen.value = value
    }
}
