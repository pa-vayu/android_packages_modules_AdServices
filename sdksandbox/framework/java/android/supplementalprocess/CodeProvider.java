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

package android.supplementalprocess;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceControlViewHost.SurfacePackage;

import java.util.concurrent.Executor;

/**
 * Encapsulates API between Supplemental process and code.
 * @hide
 */
public abstract class CodeProvider {

    private final Context mCodeContext;

    public CodeProvider(
            @NonNull Context codeContext) {
        mCodeContext = codeContext;
    }

    /**
     * Initializes code.
     */
    public abstract void initCode(
            @NonNull Bundle params, @NonNull Executor executor, @NonNull InitCodeCallback callback);

    /**
     * Returns view that will be used for remote rendering.
     */
    @NonNull
    public abstract SurfacePackage getSurfacePackage(
            @NonNull IBinder hostToken, int displayId, @NonNull Bundle params);

    /**
     * Called when extra data sent from the app is received by code.
     */
    public abstract void onExtraDataReceived(@NonNull Bundle extraData);

    /**
     * Callback for initCode.
     */
    public interface InitCodeCallback {
        /**
         * Called when code is successfully initialized.
         */
        void onInitCodeFinished(@NonNull Bundle extraParams);

        /**
         * Called when code fails to initialize.
         */
        void onInitCodeError(@Nullable String errorMessage);
    }
}
