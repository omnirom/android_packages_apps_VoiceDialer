/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.voicedialer;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.View;

import com.android.voicedialer.widget.multiwaveview.GlowPadView;
import com.android.voicedialer.widget.multiwaveview.TargetDrawable;

/**
 *
 */
public class GlowPadWrapper extends GlowPadView implements GlowPadView.OnTriggerListener {

    // Parameters for the GlowPadView "ping" animation; see triggerPing().
    private static final int PING_MESSAGE_WHAT = 101;
    private static final boolean ENABLE_PING_AUTO_REPEAT = true;
    private static final long PING_REPEAT_DELAY_MS = 1200;

    private final Handler mPingHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PING_MESSAGE_WHAT:
                    triggerPing();
                    break;
            }
        }
    };

    private boolean mPingEnabled = true;
    private boolean mTargetTriggered = false;

    public GlowPadWrapper(Context context) {
        super(context);
    }

    public GlowPadWrapper(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setOnTriggerListener(this);
    }

    public void setCenterDrawable(int resourceId) {
        TargetDrawable nDrawable = new TargetDrawable(getResources(),
                              resourceId, 1);
        setHandleDrawable(nDrawable);
    }

    public void startPing() {
        mPingEnabled = true;
        triggerPing();
    }

    public void stopPing() {
        mPingEnabled = false;
        mPingHandler.removeMessages(PING_MESSAGE_WHAT);
    }

    private void triggerPing() {
        if (mPingEnabled && !mPingHandler.hasMessages(PING_MESSAGE_WHAT)) {
            ping();

            if (ENABLE_PING_AUTO_REPEAT) {
                mPingHandler.sendEmptyMessageDelayed(PING_MESSAGE_WHAT, PING_REPEAT_DELAY_MS);
            }
        }
    }

    @Override
    public void onGrabbed(View v, int handle) {
        stopPing();
    }

    @Override
    public void onReleased(View v, int handle) {
        if (mTargetTriggered) {
            mTargetTriggered = false;
        } else {
            startPing();
        }
    }

    @Override
    public void onTrigger(View v, int target) {
    }

    @Override
    public void onGrabbedStateChange(View v, int handle) {

    }

    @Override
    public void onFinishFinalAnimation() {

    }
}
