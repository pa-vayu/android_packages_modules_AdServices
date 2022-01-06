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
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.supplemental.process.ISupplementalProcessService;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Objects;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
class SupplementalProcessServiceProviderImpl implements SupplementalProcessServiceProvider {

    private static final String TAG = "SupplementalProcessServiceProviderImpl";

    private final Object mLock = new Object();

    private final Context mContext;
    private final Injector mInjector;

    @GuardedBy("mLock")
    private final ArrayMap<UserHandle, SupplementalProcessConnection>
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
    public void bindService(int appUid, ServiceConnection serviceConnection) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(appUid);

        synchronized (mLock) {
            if (getBoundServiceForApp(appUid) != null) {
                Log.i(TAG, "Supplemental process for " + callingUser + " is already bound");
                registerApp(appUid);
                return;
            }

            Log.i(TAG, "Binding supplemental process for " + callingUser);

            SupplementalProcessConnection supplementalProcessConnection =
                    new SupplementalProcessConnection(serviceConnection);

            final Intent intent = new Intent(mInjector.getServiceClass())
                    .setPackage(mInjector.getServicePackage());
            boolean bound = mContext.bindServiceAsUser(
                    intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE,
                    callingUser);

            if (!bound) {
                mContext.unbindService(serviceConnection);
                // a way to notify manager that binding never happened
                serviceConnection.onNullBinding(null);
            }

            mUserSupplementalProcessConnections.put(callingUser, supplementalProcessConnection);

            registerApp(appUid);
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        synchronized (mLock) {
            if (mUserSupplementalProcessConnections.isEmpty()) {
                writer.println("mUserSupplementalProcessConnections is empty");
            } else {
                writer.print("mUserSupplementalProcessConnections size: ");
                writer.println(mUserSupplementalProcessConnections.size());
                for (Map.Entry<UserHandle, SupplementalProcessConnection> entry :
                        mUserSupplementalProcessConnections.entrySet()) {
                    writer.printf("userHandle: %s, isConnected: %s, apps: %s", entry.getKey(),
                            entry.getValue().isConnected(), entry.getValue().mHostingApps);
                    writer.println();
                }
            }
        }
    }

    // TODO(b/209058402): this method now is not clear as it actually unregister and unbind only
    //  if no other apps are registered to the service, it is a temp implementation until we
    //  support one SupplementalProcessService per app
    @Override
    public void unbindService(int appUid) {
        synchronized (mLock) {
            unregisterApp(appUid);

            SupplementalProcessConnection supplementalProcess =
                    getSupplementalProcessConnection(appUid);

            if (supplementalProcess == null) {
                // Skip, already unbound
                return;
            }

            if (supplementalProcess.isHostingAnyApp() ) {
                Log.i(TAG, "Skip unbinding the service as there still other registered apps");
                return;
            }

            final UserHandle callingUser = UserHandle.getUserHandleForUid(appUid);

            mContext.unbindService(supplementalProcess.getServiceConnection());
            mUserSupplementalProcessConnections.remove(callingUser);
            Log.i(TAG, "Supplemental process has been unbound");
        }
    }

    @Override
    @Nullable
    public ISupplementalProcessService getBoundServiceForApp(int appUid) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(appUid);
        synchronized (mLock) {
            if (mUserSupplementalProcessConnections.containsKey(callingUser)) {
                return Objects.requireNonNull(mUserSupplementalProcessConnections.get(callingUser))
                        .getSupplementalProcessService();
            }
        }
        return null;
    }

    @Override
    public void registerServiceForApp(int appUid, ISupplementalProcessService service) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(appUid);
        synchronized (mLock) {
            if (mUserSupplementalProcessConnections.containsKey(callingUser)) {
                Objects.requireNonNull(mUserSupplementalProcessConnections.get(callingUser))
                        .setSupplementalProcessService(service);
            }
        }
    }

    @GuardedBy("mLock")
    private void registerApp(int appUid) {
        final SupplementalProcessConnection supplementalProcess =
                getSupplementalProcessConnection(appUid);
        if (supplementalProcess == null) {
            final UserHandle callingUser = UserHandle.getUserHandleForUid(appUid);
            throw new IllegalStateException("There is no connections between app " + appUid
                    + " and service for " + callingUser);
        }
        supplementalProcess.addApp(appUid);
    }

    @GuardedBy("mLock")
    private void unregisterApp(int appUid) {
        synchronized (mLock) {
            SupplementalProcessConnection supplementalProcess =
                    getSupplementalProcessConnection(appUid);
            if (supplementalProcess == null) {
                Log.i(TAG, "Skipping unregister app " + appUid + " as service is not bound");
                return;
            }
            supplementalProcess.removeApp(appUid);
            Log.i(TAG, "unregister app " + appUid);
        }
    }


    @GuardedBy("mLock")
    @Nullable
    private SupplementalProcessConnection getSupplementalProcessConnection(int appUid) {
        final UserHandle callingUser = UserHandle.getUserHandleForUid(appUid);
        return mUserSupplementalProcessConnections.get(callingUser);
    }

    private static class SupplementalProcessConnection {
        private final ServiceConnection mServiceConnection;
        private final ArraySet<Integer> mHostingApps = new ArraySet<>();
        @Nullable
        private ISupplementalProcessService mSupplementalProcessService = null;

        SupplementalProcessConnection(ServiceConnection serviceConnection) {
            mServiceConnection = serviceConnection;
        }

        @Nullable
        public ISupplementalProcessService getSupplementalProcessService() {
            return mSupplementalProcessService;
        }

        public ServiceConnection getServiceConnection() {
            return mServiceConnection;
        }

        public void setSupplementalProcessService(ISupplementalProcessService service) {
            mSupplementalProcessService = service;
        }

        boolean isConnected() {
            return mSupplementalProcessService != null;
        }

        public void addApp(int appid) {
            mHostingApps.add(appid);
        }

        public void removeApp(int appIid) {
            mHostingApps.remove(appIid);
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
