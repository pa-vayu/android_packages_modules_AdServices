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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.supplemental.process.ISupplementalProcessService;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
class SupplementalProcessServiceProviderImpl implements SupplementalProcessServiceProvider {

    private static final String TAG = "SupplementalProcessServiceProviderImpl";

    private final Object mLock = new Object();

    private final Context mContext;
    private final Injector mInjector;

    @GuardedBy("mLock")
    private ArrayMap<UserHandle, SupplementalProcessConnection>
            mUserSupplementalProcessConnections = new ArrayMap<>();

    SupplementalProcessServiceProviderImpl(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    SupplementalProcessServiceProviderImpl(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
    }

    @Override
    @Nullable
    public ISupplementalProcessService bindService(int callingUid, IBinder appBinder) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
        synchronized (mLock) {
            Log.i(TAG, "Binding to supplemental process for " + callingUser.toString());
            if (isServiceBound(callingUid)) {
                Log.i(TAG, "Supplemental process is already bound");
                registerApp(callingUid, appBinder);
                return getSupplementalProcessConnection(callingUid).supplementalProcessService;
            }

            SupplementalProcessConnection userConnection = new SupplementalProcessConnection();
            userConnection.serviceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    userConnection.supplementalProcessService =
                            ISupplementalProcessService.Stub.asInterface(service);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // Supplemental process crashed or was killed, system will start it again.
                    // TODO(b/204991850): Handle restarts differently
                    //  (e.g. Exponential backoff retry strategy)
                    userConnection.supplementalProcessService = null;
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        unbindService(callingUid);
                        bindService(callingUid, appBinder);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            };

            final Intent intent = new Intent(mInjector.getServiceClass());
            intent.setPackage(mInjector.getServicePackage());

            boolean bound =
                    mContext.bindServiceAsUser(
                            intent,
                            userConnection.serviceConnection,
                            Context.BIND_AUTO_CREATE,
                            callingUser);

            if (!bound) {
                mContext.unbindService(userConnection.serviceConnection);
                Log.e(TAG, "Could not find supplemental process service.");
                return null;
            }

            mUserSupplementalProcessConnections.put(callingUser, userConnection);
            Log.i(TAG, "Supplemental process has been bound");

            registerApp(callingUid, appBinder);
            return getSupplementalProcessConnection(callingUid).supplementalProcessService;
        }
    }

    @GuardedBy("mLock")
    private void registerApp(int uid, IBinder appBinder) {
        SupplementalProcessConnection supplementalProcess =
                getSupplementalProcessConnection(uid);

        supplementalProcess.addApp(uid);
        try {
            appBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    unregisterApp(uid, supplementalProcess);
                }
            }, 0);
        } catch (RemoteException re) {
            // App has already died, unregister it
            unregisterApp(uid, supplementalProcess);
        }
    }

    private void unregisterApp(int uid, SupplementalProcessConnection supplementalProcess) {
        synchronized (mLock) {
            if (!isServiceBound(uid)) {
                return;
            }
            supplementalProcess.removeApp(uid);
            if (!supplementalProcess.isHostingAnyApp()) {
                unbindService(uid);
            }
        }
    }

    @GuardedBy("mLock")
    private SupplementalProcessConnection getSupplementalProcessConnection(int callingUid) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
        return mUserSupplementalProcessConnections.get(callingUser);
    }

    @Override
    public boolean isServiceBound(int callingUid) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
        synchronized (mLock) {
            return (mUserSupplementalProcessConnections.containsKey(callingUser));
        }
    }

    private void unbindService(int callingUid) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
        synchronized (mLock) {
            Log.i(TAG, "Unbinding from supplemental process");
            if (isServiceBound(callingUid)) {
                SupplementalProcessConnection userConnection =
                        getSupplementalProcessConnection(callingUid);
                mContext.unbindService(userConnection.serviceConnection);
                mUserSupplementalProcessConnections.remove(callingUser);
            }
            Log.i(TAG, "Supplemental process has been unbound");
        }
    }

    private static class SupplementalProcessConnection {
        public ServiceConnection serviceConnection = null;
        public ISupplementalProcessService supplementalProcessService = null;
        private ArraySet<Integer> mHostingApps = new ArraySet<>();

        boolean isConnected() {
            return serviceConnection != null && supplementalProcessService != null;
        }

        public void addApp(int uid) {
            mHostingApps.add(uid);
        }

        public void removeApp(int uid) {
            if (mHostingApps.contains(uid)) {
                mHostingApps.remove(uid);
            }
        }

        public boolean isHostingApp(int uid) {
            return mHostingApps.contains(uid);
        }

        public boolean isHostingAnyApp() {
            return !mHostingApps.isEmpty();
        }
    }

    @VisibleForTesting
    static class Injector {
        public String getServicePackage() {
            return "com.android.supplemental.process";
        }

        public String getServiceClass() {
            return "com.android.supplemental.process.SupplementalProcessService";
        }
    }
}
