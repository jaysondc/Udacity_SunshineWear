/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.jaysondc.sunshinewear;

import android.graphics.Color;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchfaceService extends CanvasWatchFaceService implements
        DataApi.DataListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener{

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private final String LOG_TAG = this.getClass().getSimpleName();
    private GoogleApiClient mGoogleApiClient;
    private Engine mWatchEngine;

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);


    /**
     * Store weather data once a change is synced
     */
    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        Log.d(LOG_TAG, "Detected some data changed!");

        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo(getString(R.string.PATH_WEAR_DATA)) == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                     Log.d(LOG_TAG, "Received updated weather data from Sunshine.");

                    // Update watchface with updated weather data
                    mWatchEngine.updateWeather(
                            dataMap.getString(getString(R.string.DATAMAP_TEMP_HIGH)),
                            dataMap.getString(getString(R.string.DATAMAP_TEMP_LOW)),
                            dataMap.getInt(getString(R.string.DATAMAP_WEATHER_CONDITION)));
                }
            } else if (event.getType() == DataEvent.TYPE_DELETED) {
                // DataItem deleted
            }
        }

        // Cleanup
        dataEventBuffer.release();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(LOG_TAG, "Wearable API is connected. Now listening.");
        readWeather();
        Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "Wearable API is suspended.");
        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        mGoogleApiClient.disconnect();

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.d(LOG_TAG, "Wearable API connection failed.");
    }

    @Override
    public Engine onCreateEngine() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        mGoogleApiClient.connect();

        readWeather();

        Log.d(LOG_TAG, "Watchface created.");
        mWatchEngine = new Engine();
        return mWatchEngine;
    }

    private void readWeather(){
        /**
         * Get latest weather data from Data Layer. This data should always be less than 24hrs
         * old because it's sent using the Sunshine SynAdapter.
         */

        Wearable.DataApi.getDataItems(mGoogleApiClient)
                .setResultCallback(new ResultCallback<DataItemBuffer>() {
                    @Override
                    public void onResult(@NonNull DataItemBuffer dataItems) {
                        // Read all current DataItems
                        Log.d(LOG_TAG, "Data buffer received with " + dataItems.getCount() + " items.");
                        for (DataItem item : dataItems) {
                            if (item.getUri().getPath().compareTo(getString(R.string.PATH_WEAR_DATA)) == 0) {
                                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                                Log.d(LOG_TAG, "Received latest weather data from Sunshine.");

                                // Update watchface with updated weather data
                                mWatchEngine.updateWeather(
                                        dataMap.getString(getString(R.string.DATAMAP_TEMP_HIGH)),
                                        dataMap.getString(getString(R.string.DATAMAP_TEMP_LOW)),
                                        dataMap.getInt(getString(R.string.DATAMAP_WEATHER_CONDITION)));
                            }
                        }

                        // Cleanup
                        dataItems.release();
                    }
                });
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        static final int MSG_UPDATE_TIME = 0;

        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        boolean mAmbient;

        Time mTime;

        float mXOffset = 0;
        float mYOffset = 0;

        private int specW, specH;
        private View myLayout;
        private TextView day, date, month, year, hour, colon, minute, high, low;
        private ImageView weatherIcon;
        private final Point displaySize = new Point();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchfaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchfaceService.this.getResources();

            mTime = new Time();

            // Inflate the layout that we're using for the watch face
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            myLayout = inflater.inflate(R.layout.sunshine_watchface_layout, null);

            // Load the display spec - we'll need this later for measuring myLayout
            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getRealSize(displaySize);

            // Find some views for later use
            day = (TextView) myLayout.findViewById(R.id.day);
            date = (TextView) myLayout.findViewById(R.id.date);
            month = (TextView) myLayout.findViewById(R.id.month);
            year = (TextView) myLayout.findViewById(R.id.year);
            hour = (TextView) myLayout.findViewById(R.id.hour);
            colon = (TextView) myLayout.findViewById(R.id.colon);
            minute = (TextView) myLayout.findViewById(R.id.minute);

            // Weather views
            high = (TextView) myLayout.findViewById(R.id.temp_high);
            low = (TextView) myLayout.findViewById(R.id.temp_low);
            weatherIcon = (ImageView) myLayout.findViewById(R.id.weather_icon);


        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchfaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchfaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            mXOffset = mYOffset = 0;

            // Recompute the MeasureSpec fields - these determine the actual size of the layout
            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x, View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y, View.MeasureSpec.EXACTLY);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            mAmbient = inAmbientMode;

            // Change these variables based on whether or not we're in amient mode
            Typeface font;
            int visibility;
            boolean antiAlias;

            if(inAmbientMode){
                font = Typeface.create("san-serif-condensed", Typeface.NORMAL);
                visibility = View.INVISIBLE;
                antiAlias = false;
            } else {
                font = Typeface.create("san-serif-condensed", Typeface.BOLD);
                visibility = View.VISIBLE;
                antiAlias = true;
            }

            hour.setTypeface(font);
            colon.setTypeface(font);
            high.setTypeface(font);

            hour.getPaint().setAntiAlias(antiAlias);
            colon.getPaint().setAntiAlias(antiAlias);
            minute.getPaint().setAntiAlias(antiAlias);
            day.getPaint().setAntiAlias(antiAlias);
            date.getPaint().setAntiAlias(antiAlias);
            month.getPaint().setAntiAlias(antiAlias);
            year.getPaint().setAntiAlias(antiAlias);
            high.getPaint().setAntiAlias(antiAlias);
            low.getPaint().setAntiAlias(antiAlias);

            weatherIcon.setVisibility(visibility);

            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Get the current Time
            mTime.setToNow();

            // Apply it to the date fields
            day.setText(String.format("%ta, ", mTime.toMillis(false)));
            month.setText(String.format("%tb ", mTime.toMillis(false)));
            date.setText(String.format(Locale.getDefault(), "%02d ", mTime.monthDay));
            year.setText(String.format(Locale.getDefault(), "%04d", mTime.year));

            // Apply it to the time fields
            hour.setText(String.format(Locale.getDefault(), "%02d", mTime.hour));
            minute.setText(String.format(Locale.getDefault(), "%02d", mTime.minute));

            // Make colon blink every second
            if(mTime.second%2 == 1 && !mAmbient){
                colon.setVisibility(View.INVISIBLE);
            } else {
                colon.setVisibility(View.VISIBLE);
            }

            // Update the layout
            myLayout.measure(specW, specH);
            myLayout.layout(0, 0, myLayout.getMeasuredWidth(), myLayout.getMeasuredHeight());

            // Draw it to the Canvas
            if(mAmbient){
                canvas.drawColor(getColor(R.color.black));
            } else {
                canvas.drawColor(getColor(R.color.colorPrimary));
            }
            canvas.translate(mXOffset, mYOffset);
            myLayout.draw(canvas);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }


        /**
         * Update weather components of watchface
         */
        private void updateWeather(String highTemp, String lowTemp, int weatherConditionId){
            // We assume the imperial vs metric conversion is done for us
            high.setText(highTemp);
            low.setText(lowTemp);
            // Get the proper icon given the condition ID
            weatherIcon.setImageDrawable(getDrawable(
                    getSmallArtResourceIdForWeatherCondition(weatherConditionId)));
        }

    }

    public static int getSmallArtResourceIdForWeatherCondition(int weatherId) {

        /*
         * Based on weather code data for Open Weather Map.
         */
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 771 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        } else if (weatherId >= 900 && weatherId <= 906) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 958 && weatherId <= 962) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 951 && weatherId <= 957) {
            return R.drawable.ic_clear;
        }

        Log.e("Some log", "Unknown Weather: " + weatherId);
        return R.drawable.ic_storm;
    }
}
