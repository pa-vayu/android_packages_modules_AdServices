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
 */

package com.android.tests.sdksandbox;

import static com.google.common.truth.Truth.assertThat;

import android.app.sdksandbox.SdkSandboxManager;
import android.app.sdksandbox.testutils.FakeRemoteSdkCallback;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SdkSandboxStorageTestApp {

    private static final String CODE_PROVIDER_PACKAGE =
            "com.android.tests.codeprovider.storagetest";
    private static final String CODE_PROVIDER_KEY = "sdk-provider-class";
    private static final String CODE_PROVIDER_CLASS =
            "com.android.tests.codeprovider.storagetest.StorageTestCodeProvider";

    private static final String BUNDLE_KEY_PHASE_NAME = "phase-name";

    private SdkSandboxManager mSdkSandboxManager;

    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        mSdkSandboxManager = context.getSystemService(
                SdkSandboxManager.class);
        assertThat(mSdkSandboxManager).isNotNull();
    }

    // Run a phase of the test inside the code loaded for this app
    private void runPhaseInsideCode(IBinder token, String phaseName) {
        Bundle bundle = new Bundle();
        bundle.putString(BUNDLE_KEY_PHASE_NAME, phaseName);
        mSdkSandboxManager.requestSurfacePackage(token, new Binder(), 0, bundle);
    }

    @Test
    public void testSdkSandboxDataAppDirectory_SharedStorageIsUsable() throws Exception {
        // First load code
        Bundle params = new Bundle();
        params.putString(CODE_PROVIDER_KEY, CODE_PROVIDER_CLASS);
        FakeRemoteSdkCallback callback = new FakeRemoteSdkCallback();
        mSdkSandboxManager.loadSdk(CODE_PROVIDER_PACKAGE, "1", params, callback);
        IBinder codeToken = callback.getSdkToken();

        // Run phase inside the code
        runPhaseInsideCode(codeToken, "testSdkSandboxDataAppDirectory_SharedStorageIsUsable");

        // Wait for code to finish handling the request
        assertThat(callback.isRequestSurfacePackageSuccessful()).isFalse();
    }
}
