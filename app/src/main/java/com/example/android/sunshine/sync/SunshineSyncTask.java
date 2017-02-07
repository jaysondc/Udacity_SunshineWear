/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.example.android.sunshine.sync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.CursorLoader;
import android.text.format.DateUtils;
import android.util.Log;

import com.example.android.sunshine.R;
import com.example.android.sunshine.data.SunshinePreferences;
import com.example.android.sunshine.data.WeatherContract;
import com.example.android.sunshine.utilities.NetworkUtils;
import com.example.android.sunshine.utilities.NotificationUtils;
import com.example.android.sunshine.utilities.OpenWeatherJsonUtils;
import com.example.android.sunshine.utilities.SunshineWeatherUtils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.net.URL;

import static com.example.android.sunshine.MainActivity.MAIN_FORECAST_PROJECTION;

public class SunshineSyncTask implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    /**
     * Performs the network request for updated weather, parses the JSON from that request, and
     * inserts the new weather information into our ContentProvider. Will notify the user that new
     * weather has been loaded if the user hasn't been notified of the weather within the last day
     * AND they haven't disabled notifications in the preferences screen.
     *
     * @param context Used to access utility methods and the ContentResolver
     */
    synchronized public static void syncWeather(Context context) {

        try {
            /*
             * The getUrl method will return the URL that we need to get the forecast JSON for the
             * weather. It will decide whether to create a URL based off of the latitude and
             * longitude or off of a simple location as a String.
             */
            URL weatherRequestUrl = NetworkUtils.getUrl(context);

            /* Use the URL to retrieve the JSON */
            String jsonWeatherResponse = NetworkUtils.getResponseFromHttpUrl(weatherRequestUrl);

            /* Parse the JSON into a list of weather values */
            ContentValues[] weatherValues = OpenWeatherJsonUtils
                    .getWeatherContentValuesFromJson(context, jsonWeatherResponse);

            /*
             * In cases where our JSON contained an error code, getWeatherContentValuesFromJson
             * would have returned null. We need to check for those cases here to prevent any
             * NullPointerExceptions being thrown. We also have no reason to insert fresh data if
             * there isn't any to insert.
             */
            if (weatherValues != null && weatherValues.length != 0) {
                /* Get a handle on the ContentResolver to delete and insert data */
                ContentResolver sunshineContentResolver = context.getContentResolver();

                /* Delete old weather data because we don't need to keep multiple days' data */
                sunshineContentResolver.delete(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        null,
                        null);

                /* Insert our new weather data into Sunshine's ContentProvider */
                sunshineContentResolver.bulkInsert(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        weatherValues);

                /*
                 * Finally, after we insert data into the ContentProvider, determine whether or not
                 * we should notify the user that the weather has been refreshed.
                 */
                boolean notificationsEnabled = SunshinePreferences.areNotificationsEnabled(context);

                /*
                 * If the last notification was shown was more than 1 day ago, we want to send
                 * another notification to the user that the weather has been updated. Remember,
                 * it's important that you shouldn't spam your users with notifications.
                 */
                long timeSinceLastNotification = SunshinePreferences
                        .getEllapsedTimeSinceLastNotification(context);

                boolean oneDayPassedSinceLastNotification = false;

                if (timeSinceLastNotification >= DateUtils.DAY_IN_MILLIS) {
                    oneDayPassedSinceLastNotification = true;
                }

                /*
                 * We only want to show the notification if the user wants them shown and we
                 * haven't shown a notification in the past day.
                 */
                if (notificationsEnabled && oneDayPassedSinceLastNotification) {
                    NotificationUtils.notifyUserOfNewWeather(context);
                }

            /* If the code reaches this point, we have successfully performed our sync */

            }

        } catch (Exception e) {
            /* Server probably invalid */
            e.printStackTrace();
        }

        // Sync new weather data to Android Wear
        sendWearWeatherData(context);

    }

    private static void sendWearWeatherData(Context context) {
        final String LOG_TAG = "SunshineWearSync";

        /*
        * The columns of data that we are interested in displaying within our DetailActivity's
        * weather display.
        */
        final String[] WEATHER_DETAIL_PROJECTION = {
                WeatherContract.WeatherEntry.COLUMN_DATE,
                WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
                WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
                WeatherContract.WeatherEntry.COLUMN_HUMIDITY,
                WeatherContract.WeatherEntry.COLUMN_PRESSURE,
                WeatherContract.WeatherEntry.COLUMN_WIND_SPEED,
                WeatherContract.WeatherEntry.COLUMN_DEGREES,
                WeatherContract.WeatherEntry.COLUMN_WEATHER_ID
        };

        /*
        * We store the indices of the values in the array of Strings above to more quickly be able
        * to access the data from our query. If the order of the Strings above changes, these
        * indices must be adjusted to match the order of the Strings.
        */
        final int INDEX_WEATHER_DATE = 0;
        final int INDEX_WEATHER_MAX_TEMP = 1;
        final int INDEX_WEATHER_MIN_TEMP = 2;
        final int INDEX_WEATHER_HUMIDITY = 3;
        final int INDEX_WEATHER_PRESSURE = 4;
        final int INDEX_WEATHER_WIND_SPEED = 5;
        final int INDEX_WEATHER_DEGREES = 6;
        final int INDEX_WEATHER_CONDITION_ID = 7;

        /*
         * Query weather data to send
         */
        ContentResolver sunshineContentResolver = context.getContentResolver();

        /*
         * A SELECTION in SQL declares which rows you'd like to return. In our case, we
         * want all weather data from today onwards that is stored in our weather table.
         * We created a handy method to do that in our WeatherEntry class.
         */
        String selection = WeatherContract.WeatherEntry.getSqlSelectForToday();

        // Get cursor
        Cursor myCursor = sunshineContentResolver.query(
                WeatherContract.WeatherEntry.CONTENT_URI,
                WEATHER_DETAIL_PROJECTION,
                selection,
                null,
                null
        );

        // Check if cursor contains data
        if (myCursor == null){
            Log.d(LOG_TAG, "Error: No weather data found.");
            return;
        }

        // Format data
        myCursor.moveToFirst();
        Long highInCelsius = myCursor.getLong(INDEX_WEATHER_MAX_TEMP);
        Long lowInCelsius = myCursor.getLong(INDEX_WEATHER_MIN_TEMP);
        int weatherCondition = myCursor.getInt(INDEX_WEATHER_CONDITION_ID);

        Log.d(LOG_TAG, "Pulled cursor high temp " + highInCelsius + " low " + lowInCelsius);

        String highString = SunshineWeatherUtils.formatTemperature(context, highInCelsius);
        String lowString = SunshineWeatherUtils.formatTemperature(context, lowInCelsius);

        Log.d(LOG_TAG, "Weather condition is " + weatherCondition + ".");

        // Send data to Wear Data Layer
        GoogleApiClient mGoogleApiClient;
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();

        double random = Math.floor(Math.random() * 10);
        Log.d(LOG_TAG, "Sending the number " + random + " to Android Wear.");
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(context.getString(R.string.PATH_WEAR_DATA));

        putDataMapReq.getDataMap().putString(context.getString(
                R.string.DATAMAP_TEMP_HIGH),
                highString);
        putDataMapReq.getDataMap().putString(context.getString(
                R.string.DATAMAP_TEMP_LOW),
                lowString);
        putDataMapReq.getDataMap().putInt(context.getString(
                R.string.DATAMAP_WEATHER_CONDITION),
                weatherCondition);
        // Sending current time so a change is detected every time for debug
        putDataMapReq.getDataMap().putLong(context.getString(
                R.string.DATAMAP_LAST_UPDATED),
                System.currentTimeMillis());

        putDataMapReq.setUrgent();
        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();

        // Send DataItem to Android Wear Buffer to be synced when possible
        PendingResult<DataApi.DataItemResult> pendingResult =
                Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq);

        pendingResult.setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
            @Override
            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                Log.d(LOG_TAG, "Result is " + dataItemResult.getStatus() + ".");
            }
        });

        // cleanup
        myCursor.close();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }
}