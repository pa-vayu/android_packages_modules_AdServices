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

import com.android.supplemental.process.ISupplementalProcessService;

import java.io.PrintWriter;

/**
 * Interface to get hold of SupplementalProcessService
 */
public interface SupplementalProcessServiceProvider {
    /**
     * Initiate a connection with SupplementalProcessService and register the app to it
     *
     * <p>Return SupplementalProcessService connected for {@code callingUid} or null on error.
     */
    ISupplementalProcessService bindService(int appUid);

    /** Unregister the app from its corresponding SupplementalProcessService and unbinding
     * the service if there is no other apps registered to it.
     */
    void unbindService(int appUid);

    /** Check if connection with the supplemental process for the app with given
     * {@code appUid} is established. */
    boolean isServiceBound(int appUid);

    /** Dump debug information for adb shell dumpsys */
    default void dump(PrintWriter writer) {
    }
}
