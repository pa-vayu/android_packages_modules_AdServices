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

package com.android.server.supplementalprocess;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.supplementalprocess.IRemoteCodeCallback;
import android.supplementalprocess.SupplementalProcessManager;
import android.supplementalprocess.testutils.FakeRemoteCodeCallback;
import android.util.ArrayMap;

import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.supplemental.process.ISupplementalProcessManagerToSupplementalProcessCallback;
import com.android.supplemental.process.ISupplementalProcessService;
import com.android.supplemental.process.ISupplementalProcessToSupplementalProcessManagerCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit tests for {@link SupplementalProcessManagerService}.
 */
public class SupplementalProcessManagerServiceUnitTest {

    private SupplementalProcessManagerService mService;
    private FakeSupplementalProcessService mSupplementalProcessService;
    private FakeSupplementalProcessProvider mProvider;
    private static final String CODE_PROVIDER_PACKAGE = "com.android.codeprovider";

    @Before
    public void setup() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mSupplementalProcessService = new FakeSupplementalProcessService();
        mProvider = new FakeSupplementalProcessProvider(mSupplementalProcessService);
        mService = new SupplementalProcessManagerService(context, mProvider);
    }

    @Test
    public void testLoadCodeIsSuccessful() throws Exception {
        FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        // Assume SupplementalProcess loads successfully
        mSupplementalProcessService.sendLoadCodeSuccessful();
        assertThat(callback.isLoadCodeSuccessful()).isTrue();
    }

    @Test
    public void testLoadCodePackageDoesNotExist() throws Exception {
        FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
        mService.loadCode("does.not.exist", "1", new Bundle(), callback);

        // Verify loading failed
        assertThat(callback.isLoadCodeSuccessful()).isFalse();
        assertThat(callback.getLoadCodeErrorCode())
                .isEqualTo(SupplementalProcessManager.LOAD_CODE_NOT_FOUND);
        assertThat(callback.getLoadCodeErrorMsg()).contains("not found for loading");
    }

    @Test
    public void testLoadCode_errorFromSupplementalProcess() throws Exception {
        FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        mSupplementalProcessService.sendLoadCodeError();

        // Verify loading failed
        assertThat(callback.isLoadCodeSuccessful()).isFalse();
        assertThat(callback.getLoadCodeErrorCode()).isEqualTo(
                SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR);
    }

    @Test
    public void testLoadCode_successOnFirstLoad_errorOnLoadAgain() throws Exception {
        // Load it once
        {
            FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Assume SupplementalProcess loads successfully
            mSupplementalProcessService.sendLoadCodeSuccessful();
            assertThat(callback.isLoadCodeSuccessful()).isTrue();
        }

        // Load it again
        {
            FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Verify loading failed
            assertThat(callback.isLoadCodeSuccessful()).isFalse();
            assertThat(callback.getLoadCodeErrorCode()).isEqualTo(
                    SupplementalProcessManager.LOAD_CODE_ALREADY_LOADED);
            assertThat(callback.getLoadCodeErrorMsg()).contains("has been loaded already");
        }
    }

    @Test
    public void testLoadCode_errorOnFirstLoad_canBeLoadedAgain() throws Exception {
        // Load code, but make it fail
        {
            FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Assume SupplementalProcess load fails
            mSupplementalProcessService.sendLoadCodeError();
            assertThat(callback.isLoadCodeSuccessful()).isFalse();
        }

        // Caller should be able to retry loading the code
        {
            FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
            mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
            // Assume SupplementalProcess loads successfully
            mSupplementalProcessService.sendLoadCodeSuccessful();
            assertThat(callback.isLoadCodeSuccessful()).isTrue();
        }
    }

    @Test
    public void testRequestSurfacePackageCodeNotLoaded() {
        // Trying to request package without using proper codeToken should fail
        SecurityException thrown = assertThrows(
                SecurityException.class,
                () -> mService.requestSurfacePackage(new Binder(), new Binder(),
                                                     0, new Bundle())
        );
        assertThat(thrown).hasMessageThat().contains("codeToken is invalid");
    }

    @Test
    public void testRequestSurfacePackage() throws Exception {
        // 1. We first need to collect a proper codeToken by calling loadCode
        FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        mSupplementalProcessService.sendLoadCodeSuccessful();
        assertThat(callback.isLoadCodeSuccessful()).isTrue();

        // Verify codeToken is not null
        IBinder codeToken = callback.getCodeToken();
        assertThat(codeToken).isNotNull();

        // 2. Call request package with the retrieved codeToken
        mService.requestSurfacePackage(codeToken, new Binder(), 0, new Bundle());
        mSupplementalProcessService.sendSurfacePackageReady();
        assertThat(callback.isRequestSurfacePackageSuccessful()).isTrue();
    }

    @Test
    public void testRequestSurfacePackageFailedAfterAppDied() throws Exception {
        FakeRemoteCodeCallback callback = Mockito.spy(new FakeRemoteCodeCallback());
        Mockito.doReturn(Mockito.mock(Binder.class)).when(callback).asBinder();

        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient = ArgumentCaptor
                .forClass(IBinder.DeathRecipient.class);

        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        mSupplementalProcessService.sendLoadCodeSuccessful();
        assertThat(callback.isLoadCodeSuccessful()).isTrue();

        Mockito.verify(callback.asBinder())
                .linkToDeath(deathRecipient.capture(), ArgumentMatchers.eq(0));

        // App Died
        deathRecipient.getValue().binderDied();

        // After App Died
        SecurityException thrown = assertThrows(
                SecurityException.class,
                () -> mService.requestSurfacePackage(callback.getCodeToken(), new Binder(),
                        0, new Bundle())
        );
        assertThat(thrown).hasMessageThat().contains("codeToken is invalid");
    }

    @Test
    public void testSurfacePackageError() throws Exception {
        FakeRemoteCodeCallback callback = new FakeRemoteCodeCallback();
        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);
        // Assume SurfacePackage encounters an error.
        mSupplementalProcessService.sendSurfacePackageError(
                SupplementalProcessManager.SURFACE_PACKAGE_INTERNAL_ERROR, "bad surface");
        assertThat(callback.getSurfacePackageErrorMsg()).contains("bad surface");
        assertThat(callback.getSurfacePackageErrorCode())
                .isEqualTo(SupplementalProcessManager.SURFACE_PACKAGE_INTERNAL_ERROR);
    }

    @Test(expected = SecurityException.class)
    public void testDumpWithoutPermission() {
        mService.dump(new FileDescriptor(), new PrintWriter(new StringWriter()), new String[0]);
    }

    @Test
    public void testSupplementalProcessUnbindingWhenAppDied() throws Exception {
        IRemoteCodeCallback.Stub callback = Mockito.spy(IRemoteCodeCallback.Stub.class);
        int callingUid = Binder.getCallingUid();
        assertThat(mProvider.getBoundServiceForApp(callingUid)).isNull();

        mService.loadCode(CODE_PROVIDER_PACKAGE, "123", new Bundle(), callback);

        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient = ArgumentCaptor
                .forClass(IBinder.DeathRecipient.class);
        Mockito.verify(callback.asBinder(), Mockito.times(1))
                .linkToDeath(deathRecipient.capture(), Mockito.eq(0));

        assertThat(mProvider.getBoundServiceForApp(callingUid)).isNotNull();
        deathRecipient.getValue().binderDied();
        assertThat(mProvider.getBoundServiceForApp(callingUid)).isNull();
    }

    /**
     * Fake service provider that returns local instance of {@link FakeSupplementalProcessService}
     */
    private static class FakeSupplementalProcessProvider
            implements SupplementalProcessServiceProvider {
        private final ISupplementalProcessService mSupplementalProcessService;
        private final ArrayMap<UserHandle, ISupplementalProcessService> mService = new ArrayMap<>();

        FakeSupplementalProcessProvider(ISupplementalProcessService service) {
            mSupplementalProcessService = service;
        }

        @Override
        public void bindService(int callingUid, ServiceConnection serviceConnection) {
            final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
            if (mService.containsKey(callingUser)) {
                return;
            }
            mService.put(callingUser, mSupplementalProcessService);
            serviceConnection.onServiceConnected(null, mSupplementalProcessService.asBinder());
        }

        @Override
        public void unbindService(int callingUid) {
            final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
            mService.remove(callingUser);
        }

        @Nullable
        @Override
        public ISupplementalProcessService getBoundServiceForApp(int callingUid) {
            final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
            return mService.get(callingUser);
        }

        @Override
        public void registerServiceForApp(int callingUid,
                @Nullable ISupplementalProcessService service) {
            final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
            mService.put(callingUser, service);
        }
    }

    public static class FakeSupplementalProcessService extends ISupplementalProcessService.Stub {
        private ISupplementalProcessToSupplementalProcessManagerCallback mCodeToManagerCallback;
        private final ISupplementalProcessManagerToSupplementalProcessCallback
                mManagerToCodeCallback;

        boolean mSurfacePackageRequested = false;

        FakeSupplementalProcessService() {
            mManagerToCodeCallback = new FakeManagerToCodeCallback();
        }

        @Override
        public void loadCode(IBinder codeToken, ApplicationInfo info, String codeProviderClassName,
                Bundle params, ISupplementalProcessToSupplementalProcessManagerCallback callback) {
            mCodeToManagerCallback = callback;
        }

        void sendLoadCodeSuccessful() throws RemoteException {
            mCodeToManagerCallback.onLoadCodeSuccess(new Bundle(), mManagerToCodeCallback);
        }

        void sendLoadCodeError() throws RemoteException {
            mCodeToManagerCallback.onLoadCodeError(
                    SupplementalProcessManager.LOAD_CODE_INTERNAL_ERROR, "Internal error");
        }

        void sendSurfacePackageReady() throws RemoteException {
            if (mSurfacePackageRequested) {
                mCodeToManagerCallback.onSurfacePackageReady(
                        /*hostToken=*/null, /*displayId=*/0, /*params=*/null);
            }
        }

        void sendSurfacePackageError(int errorCode, String errorMsg) throws RemoteException {
            mCodeToManagerCallback.onSurfacePackageError(errorCode, errorMsg);
        }

        private class FakeManagerToCodeCallback extends
                ISupplementalProcessManagerToSupplementalProcessCallback.Stub {
            @Override
            public void onSurfacePackageRequested(IBinder hostToken,
                    int displayId, Bundle extraParams) {
                mSurfacePackageRequested = true;
            }
        }
    }
}
