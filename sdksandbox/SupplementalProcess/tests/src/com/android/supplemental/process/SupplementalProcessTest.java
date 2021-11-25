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

package com.android.supplemental.process;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.view.SurfaceControlViewHost;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class SupplementalProcessTest {

    private Context mContext = InstrumentationRegistry.getContext();
    private SupplementalProcessServiceImpl mService;
    private ApplicationInfo mApplicationInfo;
    private static final String CODE_PROVIDER_CLASS = "com.android.testprovider.TestProvider";

    @Before
    public void setup() throws Exception {
        mService = new FakeSupplementalProcessService();
        mApplicationInfo = mContext.getPackageManager().getApplicationInfo(
                "com.android.testprovider", 0);
    }

    @Test
    public void testLoadingSuccess() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        RemoteCode mRemoteCode = new RemoteCode(latch);
        mService.loadCode(new Binder(), mApplicationInfo, CODE_PROVIDER_CLASS,
                new Bundle(), mRemoteCode);
        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(mRemoteCode.mSuccessful).isTrue();
    }

    @Test
    public void testDuplicateLoadingFails() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        RemoteCode mRemoteCode = new RemoteCode(latch);
        IBinder duplicateToken = new Binder();
        mService.loadCode(duplicateToken, mApplicationInfo, CODE_PROVIDER_CLASS,
                new Bundle(), mRemoteCode);
        mService.loadCode(duplicateToken, mApplicationInfo, CODE_PROVIDER_CLASS,
                new Bundle(), mRemoteCode);
        assertThat(latch.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(mRemoteCode.mSuccessful).isFalse();
        assertThat(mRemoteCode.mErrorCode).isEqualTo(
                ISupplementalProcessToSupplementalProcessManagerCallback.LOAD_CODE_ALREADY_LOADED);
    }

    @Test
    public void testLoadingMultiple() throws Exception {
        CountDownLatch latch1 = new CountDownLatch(1);
        RemoteCode mRemoteCode1 = new RemoteCode(latch1);
        CountDownLatch latch2 = new CountDownLatch(1);
        RemoteCode mRemoteCode2 = new RemoteCode(latch2);
        mService.loadCode(new Binder(), mApplicationInfo, CODE_PROVIDER_CLASS,
                new Bundle(), mRemoteCode1);
        mService.loadCode(new Binder(), mApplicationInfo, CODE_PROVIDER_CLASS,
                new Bundle(), mRemoteCode2);
        assertThat(latch1.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(mRemoteCode1.mSuccessful).isTrue();
        assertThat(latch2.await(1, TimeUnit.MINUTES)).isTrue();
        assertThat(mRemoteCode2.mSuccessful).isTrue();
    }

    private static class RemoteCode
            extends ISupplementalProcessToSupplementalProcessManagerCallback.Stub {

        private final CountDownLatch mLatch;
        boolean mSuccessful = false;
        int mErrorCode = -1;

        RemoteCode(CountDownLatch latch) {
            mLatch = latch;
        }

        @Override
        public void onLoadCodeSuccess(Bundle params,
                ISupplementalProcessManagerToSupplementalProcessCallback callback)  {
            mLatch.countDown();
            mSuccessful = true;
        }

        @Override
        public void onLoadCodeError(int errorCode, String message) {
            mLatch.countDown();
            mErrorCode = errorCode;
            mSuccessful = false;
        }

        @Override
        public void onSurfacePackageReady(
                SurfaceControlViewHost.SurfacePackage surfacePackage,
                int displayId, Bundle params) {}
    }

    private static class FakeSupplementalProcessService extends SupplementalProcessServiceImpl {
        @Override
        int getCallingUid() {
            return Process.SYSTEM_UID;
        }

        @Override
        Context getContext() {
            return InstrumentationRegistry.getContext();
        }
    }
}
