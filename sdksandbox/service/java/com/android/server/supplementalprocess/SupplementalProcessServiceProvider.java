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
import android.content.ServiceConnection;

import com.android.supplemental.process.ISupplementalProcessService;

import java.io.PrintWriter;

/**
 * Interface to get hold of SupplementalProcessService
 *
 * @hide
 */
public interface SupplementalProcessServiceProvider {
    /**
     * Bind to and establish a connection with SupplementalProcessService.
     * @param appUid is the calling app Uid.
     * @param serviceConnection is the serviceConnection which is going to be used to establish
     *                          the connection with SupplementalProcessService, then
     *                          ManagerService can keep updated about connection status.
     */
    void bindService(int appUid, ServiceConnection serviceConnection);

    /**
     * Unbind the SupplementalProcessService associated with the app.
     */
    void unbindService(int appUid);

    /**
     * Return bound SupplementalProcessService connected for {@code appUid} or otherwise null.
    */
    @Nullable
    ISupplementalProcessService getBoundServiceForApp(int appUid);

    /**
     * Set bound supplemental service for {@code appUid}.
     */
    void setBoundServiceForApp(int appUid, @Nullable ISupplementalProcessService service);

    /** Dump debug information for adb shell dumpsys */
    default void dump(PrintWriter writer) {
    }
}
