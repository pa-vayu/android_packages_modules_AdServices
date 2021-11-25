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

package com.android.testprovider;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.supplementalprocess.CodeProvider;
import android.view.SurfaceControlViewHost;

import java.util.concurrent.Executor;

public class TestProvider extends CodeProvider {

    public TestProvider(Context context) {
        super(context);
    }

    @Override
    public void initCode(Bundle params, Executor executor, InitCodeCallback callback) {
        callback.onInitCodeFinished(params);
    }

    @Override
    public SurfaceControlViewHost.SurfacePackage getSurfacePackage(IBinder hostToken, int displayId,
            Bundle params) {
        return null;
    }

    @Override
    public void onExtraDataReceived(Bundle extraData) {

    }
}
