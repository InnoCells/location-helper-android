package com.inqbarna.iqlocation;

import android.content.ContentResolver;
import android.content.Context;
import android.location.Address;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.LocationSource;
import com.inqbarna.iqlocation.util.ExecutorServiceScheduler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * Created by David García <david.garcia@inqbarna.com> on 26/11/14.
 */
public class LocationHelper implements GoogleApiClient.ConnectionCallbacks {

    private static final String TAG = "IQLocation";
    public static final long LONGER_INTERVAL_MILLIS  = 60 * 60 * 1000; // 60 minutes in millis
    public static final long FASTEST_INTERVAL_MILLIS = 60 * 1000; // 1 minute in millis

    private GoogleApiClient apiClient;
    private Context         appContext;

    private       ArrayList<Subscriber<? super Location>> subscribers;
    private       Observable<Location>                    observable;
    private final LocationRequest                         locationRequest;

    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            dispatchNewLocation(location);
        }
    };

    private ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> sheduledTask;
    private ExecutorServiceScheduler rxScheduler = new ExecutorServiceScheduler(executorService);

    public static Func1<? super Location, Boolean> NOT_NULL = new Func1<Location, Boolean>() {
        @Override
        public Boolean call(Location location) {
            return location != null;
        }
    };


    private synchronized void dispatchNewLocation(Location location) {
        Iterator<Subscriber<? super Location>> iterator = subscribers.iterator();
        while (iterator.hasNext()) {
            final Subscriber<? super Location> subscriber = iterator.next();
            if (!subscriber.isUnsubscribed()) {
                subscriber.onNext(location);
            } else {
                iterator.remove();
            }
        }

        if (subscribers.isEmpty()) {
            endClient();
        }
    }

    private void endClient() {
        apiClient.disconnect();
        synchronized (this) {
            if (null != sheduledTask) {
                sheduledTask.cancel(false);
                sheduledTask = null;
            }
        }
    }

    public boolean isLocationEnabled() {
        return isLocationEnabled(false);
    }

    public boolean isLocationEnabled(boolean highAccuracyRequired) {

        ContentResolver resolver = appContext.getContentResolver();

        boolean enabled = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            String allowed = Settings.Secure.getString(resolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
            if (!TextUtils.isEmpty(allowed)) {
                if (highAccuracyRequired) {
                    if (allowed.contains(LocationManager.GPS_PROVIDER) && allowed.contains(LocationManager.NETWORK_PROVIDER)) {
                        enabled = true;
                    }
                } else {
                    if (allowed.contains(LocationManager.GPS_PROVIDER) || allowed.contains(LocationManager.NETWORK_PROVIDER)) {
                        enabled = true;
                    }
                }
            }
        } else {
            int mode = Settings.Secure.getInt(resolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
            if (highAccuracyRequired) {
                enabled = mode == Settings.Secure.LOCATION_MODE_HIGH_ACCURACY;
            } else {
                enabled = mode != Settings.Secure.LOCATION_MODE_OFF;
            }
        }

        return enabled;
    }

    public Observable<List<Address>> getAddressesAtMyLocation(final int maxResults) {
        return observable.first(NOT_NULL).observeOn(rxScheduler).map(
                new Func1<Location, List<Address>>() {
                    @Override
                    public List<Address> call(Location location) {
                        List<Address> addresses = Geocoder.getFromLocation(
                                location.getLatitude(), location.getLongitude(), maxResults, Locale.getDefault().getLanguage());

                        if (null == addresses) {
                            return Collections.emptyList();
                        } else {
                            return addresses;
                        }

                    }
                });
    }

    public LocationHelper(Context context) {
        this(context, LONGER_INTERVAL_MILLIS, FASTEST_INTERVAL_MILLIS);
    }

    public LocationHelper(Context context, long longerIntervalMillis, long fastestIntervalMillis) {
        this.appContext = context.getApplicationContext();
        GoogleApiClient.Builder builder = new GoogleApiClient.Builder(appContext);
        builder.addApi(LocationServices.API).addConnectionCallbacks(this);

        apiClient = builder.build();

        subscribers = new ArrayList<>();
        createObservable();

        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY)
                       .setInterval(longerIntervalMillis)
                       .setFastestInterval(fastestIntervalMillis);
    }


    /**
     * The returned observable is not guaranteed to deliver results in main thread, if you want to ensure that
     * use the {@link rx.Observable#observeOn(rx.Scheduler)} giving the {@link rx.android.schedulers.AndroidSchedulers#mainThread()} as argument
     *
     * If interested in just the first not null location consider the operator
     * {@link rx.Observable#first(rx.functions.Func1)}
     *
     *
     *
     * @return the observable that will emit locations as known
     */
    public Observable<Location> getLocation() {
        return observable;
    }

    private void createObservable() {
        observable = Observable.create(new Observable.OnSubscribe<Location>() {
            @Override
            public void call(Subscriber<? super Location> subscriber) {

                if (!isLocationEnabled()) {
                    subscriber.onCompleted();
                    return;
                }

                synchronized (LocationHelper.this) {
                    subscribers.add(subscriber);
                }
                if (!apiClient.isConnected() && !apiClient.isConnecting()) {
                    connectApiClient();
                } else {
                    dispatchNewLocation(LocationServices.FusedLocationApi.getLastLocation(apiClient));
                }
            }
        });
    }

    private void connectApiClient() {
        apiClient.connect();
        synchronized (this) {
            if (null == sheduledTask) {
                sheduledTask = executorService.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        if (!checkSubscriversAlive()) {
                            endClient();
                        }
                    }
                }, 1, 1, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        if (!checkSubscriversAlive()) {
            endClient();
        } else {
            dispatchNewLocation(LocationServices.FusedLocationApi.getLastLocation(apiClient));
            LocationServices.FusedLocationApi.requestLocationUpdates(apiClient, locationRequest, locationListener);
        }
    }

    private synchronized boolean checkSubscriversAlive() {
        Iterator<Subscriber<? super Location>> iterator = subscribers.iterator();
        while (iterator.hasNext()) {
            Subscriber<? super Location> subscriber = iterator.next();
            if (subscriber.isUnsubscribed()) {
                iterator.remove();
            }
        }
        return !subscribers.isEmpty();
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    public LocationSource newLocationSource() {
        return new MapLocationSource(this);
    }

    private static class MapLocationSource implements LocationSource {
        private LocationHelper helper;
        private WeakReference<OnLocationChangedListener> locationChangedListener;
        private Subscription suscription;

        private MapLocationSource(LocationHelper helper) {
            this.helper = helper;
        }

        @Override
        public void activate(OnLocationChangedListener onLocationChangedListener) {
            this.locationChangedListener = new WeakReference<>(onLocationChangedListener);
            suscription = helper.getLocation().filter(
                    new Func1<Location, Boolean>() {
                        @Override
                        public Boolean call(Location location) {
                            return null != location;
                        }
                    }
            ).subscribe(
                    new Action1<Location>() {
                        @Override
                        public void call(Location location) {
                            deliverLocation(location);
                        }
                    },
                    new Action1<Throwable>() {
                        @Override
                        public void call(Throwable throwable) {
                            Log.e(TAG, "Error getting location update", throwable);
                            finishSubscription(false);
                        }
                    },
                    new Action0() {
                        @Override
                        public void call() {
                            finishSubscription(false);
                        }
                    }
            );
        }

        private void finishSubscription(boolean unsubscribe) {
            if (null != suscription) {
                if (unsubscribe) {
                    suscription.unsubscribe();
                }
                suscription = null;
            }
        }

        private void deliverLocation(Location location) {
            if (null != locationChangedListener) {
                Log.d(TAG, "Delivering new location: " + location);
                final OnLocationChangedListener onLocationChangedListener = locationChangedListener.get();
                if (onLocationChangedListener != null) {
                    onLocationChangedListener.onLocationChanged(location);
                } else {
                    Log.d(TAG, "But no receptor ready");
                    finishSubscription(true);
                    locationChangedListener = null;
                }
            }
        }

        @Override
        public void deactivate() {
            finishSubscription(true);
            locationChangedListener = null;
        }
    }
}
