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

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.supplementalprocess.CodeContext;
import android.supplementalprocess.CodeProvider;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;

import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Random;

/**
 * A holder for loaded code.
 */
class CodeHolder {

    private static final String TAG = "SupplementalProcess";

    private boolean mInitialized = false;
    private ISupplementalProcessToSupplementalProcessManagerCallback mCallback;
    private CodeProvider mCode;
    private Context mContext;

    private DisplayManager mDisplayManager;
    private final Random mRandom = new SecureRandom();
    private final SparseArray<SurfaceControlViewHost.SurfacePackage> mSurfacePackages =
            new SparseArray<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    void init(Context context, Bundle params,
            ISupplementalProcessToSupplementalProcessManagerCallback callback,
            String codeProviderClassName, ClassLoader loader, CodeContext codeContext) {
        if (mInitialized) {
            throw new IllegalStateException("Already initialized!");
        }
        mInitialized = true;
        mCallback = callback;
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        try {
            Class<?> clz = Class.forName(codeProviderClassName, true, loader);
            mCode = (CodeProvider) clz.getConstructor().newInstance();
            mCode.initCode(codeContext, params, mContext.getMainExecutor(),
                    new CodeProvider.InitCodeCallback() {
                @Override
                public void onInitCodeFinished(Bundle extraParams) {
                    sendLoadCodeSuccess();
                }

                @Override
                public void onInitCodeError(String errorMessage) {
                    sendLoadCodeError(errorMessage);
                }
            });
        } catch (ClassNotFoundException e) {
            sendLoadCodeError("Could not find class: " + codeProviderClassName);
        } catch (Exception e) {
            sendLoadCodeError("Could not instantiate CodeProvider: " + e);
        } catch (Throwable e) {
            sendLoadCodeError("Error thrown during init: " + e);
        }
    }

    void dump(PrintWriter writer) {
        writer.print("mInitialized: " + mInitialized);
        final String mCodeClass = mCode == null ? "null" : mCode.getClass().getName();
        writer.println(" mCode class: " + mCodeClass);
    }

    private void sendLoadCodeSuccess() {
        try {
            mCallback.onLoadCodeSuccess(new Bundle(), new SupplementalProcessCallbackImpl());
        } catch (RemoteException e) {
            Log.e(TAG, "Could not send onLoadCodeSuccess: " + e);
        }
    }

    private void sendSurfacePackageError(String errorMessage) {
        try {
            mCallback.onSurfacePackageError(
                    ISupplementalProcessToSupplementalProcessManagerCallback
                            .SURFACE_PACKAGE_INTERNAL_ERROR,
                    errorMessage);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not send onSurfacePackageError: " + e);
        }
    }

    private void sendLoadCodeError(String errorMessage) {
        try {
            mCallback.onLoadCodeError(
                    ISupplementalProcessToSupplementalProcessManagerCallback
                            .LOAD_CODE_PROVIDER_INIT_ERROR,
                    errorMessage);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not send onLoadCodeError: " + e);
        }
    }

    private int allocateSurfacePackageId(SurfaceControlViewHost.SurfacePackage surfacePackage) {
        synchronized (mSurfacePackages) {
            for (int i = 0; i < 32; i++) {
                int id = mRandom.nextInt();
                if (!mSurfacePackages.contains(id)) {
                    mSurfacePackages.put(id, surfacePackage);
                    return id;
                }
            }
            throw new IllegalStateException("Could not allocate surfacePackageId");
        }
    }

    private class SupplementalProcessCallbackImpl
            extends ISupplementalProcessManagerToSupplementalProcessCallback.Stub {

        @Override
        public void onSurfacePackageRequested(IBinder token, int displayId, Bundle params) {
            try {
                Context displayContext = mContext.createDisplayContext(
                        mDisplayManager.getDisplay(displayId));
                // TODO(b/209009304): Support other window contexts?
                Context windowContext = displayContext.createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, null);
                final View view = mCode.getView(windowContext, params);
                // Creating a SurfaceControlViewHost needs to done on the handler thread.
                mHandler.post(() -> {
                    try {
                        SurfaceControlViewHost host = new SurfaceControlViewHost(windowContext,
                                mDisplayManager.getDisplay(displayId), token);
                        int width = params.getInt(SupplementalProcessServiceImpl.WIDTH_KEY, 500);
                        int height = params.getInt(SupplementalProcessServiceImpl.HEIGHT_KEY, 500);
                        host.setView(view, width, height);
                        SurfaceControlViewHost.SurfacePackage surfacePackage =
                                host.getSurfacePackage();
                        int surfacePackageId = allocateSurfacePackageId(surfacePackage);
                        mCallback.onSurfacePackageReady(surfacePackage, surfacePackageId, params);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Could not send onSurfacePackageReady", e);
                    } catch (Throwable e) {
                        sendSurfacePackageError("Error thrown while getting surface package: " + e);
                    }
                });
            } catch (Throwable e) {
                sendSurfacePackageError("Error thrown while getting surface package: " + e);
            }
        }
    }
}
