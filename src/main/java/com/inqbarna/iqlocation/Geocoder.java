package com.inqbarna.iqlocation;

/**
 * Created by oriolfarrus on 13/02/15.
 */

import android.location.Address;
import android.os.Bundle;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.inqbarna.iqlocation.util.GeocoderError;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Geocoder {

    private static final String TAG                  = "IQGeocoder";
    public static final  String LOCATION_TYPE        = "location_type";
    public static final  String LOCATION_ROOFTOP     = "ROOFTOP";
    public static final  String LOCATION_APPROXIMATE = "APPROXIMATE";


    private static boolean DEBUG_PRINT = false;

    public static void setDebug(boolean enable) {
        DEBUG_PRINT = enable;
    }

    public static List<Address> getFromLocation(double lat, double lng, int maxResult, String languageCode) {

        if (Looper.getMainLooper().equals(Looper.myLooper())) {
            throw new IllegalStateException("Cannot run this method from UI thread");
        }

        String address = String.format(
                Locale.ENGLISH, "http://maps.googleapis.com/maps/api/geocode/json?latlng=%1$f,%2$f&sensor=false&language=" + languageCode,
                lat, lng);

        if (DEBUG_PRINT) {
            Log.d(TAG, "Geocoder request: " + address);
        }


        OkHttpClient client = new OkHttpClient();

        Request.Builder builder = new Request.Builder();

        builder.get().url(HttpUrl.parse(address));
        final Call call = client.newCall(builder.build());

//        client.getParams().setParameter(AllClientPNames.USER_AGENT, "Mozilla/5.0 (Java) Gecko/20081007 java-geocoder");


        List<Address> retList = null;

        try {
            final Response response = call.execute();
            JSONObject jsonObject = new JSONObject(response.body().string());

            retList = new ArrayList<Address>();

            final String status = jsonObject.getString("status");
            if ("OK".equalsIgnoreCase(status)) {
                JSONArray results = jsonObject.getJSONArray("results");
                if (results.length() > 0) {
                    for (int i = 0; i < results.length() && i < maxResult; i++) {
                        JSONObject result = results.getJSONObject(i);

                        if (DEBUG_PRINT) {
                            Log.d(TAG, result.toString());
                        }

                        Address addr = new Address(Locale.getDefault());
                        /*String formattedAddress = null;
                        if (result.has("formatted_address")) {
                            formattedAddress = result.getString("formatted_address");
                        }
                        */

                        if (result.has("geometry")) {
                            JSONObject geometry = result.getJSONObject("geometry");
                            if (geometry.has(LOCATION_TYPE)) {
                                String locationType = geometry.getString(LOCATION_TYPE);
                                Bundle bundle = new Bundle();
                                bundle.putString(LOCATION_TYPE, locationType);
                                addr.setExtras(bundle);
                            }

                            if (geometry.has("location")) {
                                final JSONObject location = geometry.getJSONObject("location");
                                addr.setLatitude(location.getDouble("lat"));
                                addr.setLongitude(location.getDouble("lng"));
                            }
                        }

                        JSONArray components = result.getJSONArray("address_components");
                        String streetNumber = null;
                        String route = null;

                        SparseArray<String> adminAreas = new SparseArray<>();

                        for (int a = 0; a < components.length(); a++) {

                            JSONObject component = components.getJSONObject(a);
                            JSONArray types = component.getJSONArray("types");
                            for (int j = 0; j < types.length(); j++) {
                                String type = types.getString(j);
                                if (type.equals("locality")) {
                                    addr.setLocality(component.getString("long_name"));
                                } else if (type.equals("sublocality")) {
                                    addr.setSubLocality(component.getString("long_name"));
                                } else if (type.equals("street_number")) {
                                    streetNumber = component.getString("long_name");
                                } else if (type.equals("route") || type.equals("street_name")) {
                                    route = component.getString("long_name");
                                } else if (type.equals("country")) {
                                    addr.setCountryCode(component.getString("short_name"));
                                    addr.setCountryName(component.getString("long_name"));
                                } else if (type.equals("administrative_area_level_2")) {
                                    adminAreas.put(1, component.getString("long_name"));
                                } else if (type.equals("administrative_area_level_1")) {
                                    adminAreas.put(0, component.getString("long_name"));
                                } else if (type.equals("administrative_area_level_3")) {
                                    adminAreas.put(2, component.getString("long_name"));
                                } else if (type.equals("administrative_area_level_4")) {
                                    adminAreas.put(3, component.getString("long_name"));
                                } else if (type.equals("postal_code")) {
                                    addr.setPostalCode(component.getString("long_name"));
                                }
                            }
                        }


                        if (adminAreas.size() >= 1) {
                            addr.setAdminArea(adminAreas.valueAt(0));
                        }

                        if (adminAreas.size() >= 2) {
                            addr.setSubAdminArea(adminAreas.valueAt(1));
                        }

                        if (null != route && null != streetNumber) {
                            addr.setAddressLine(0, route + " " + streetNumber);
                        }

                        retList.add(addr);
                    }
                }
            } else {
                throw new GeocoderError(status);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error calling Google geocode webservice.", e);
            throw new GeocoderError("Error calling Google geocode webservice " + e.getMessage(), null); // because somehow stacktrace not printing
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing Google geocode webservice response.", e);
            throw new GeocoderError("Error parsing Google geocode webservice response.", e);
        } catch (Exception e) {
            Log.e(TAG, "Unknown error in geocoder", e);
            throw new GeocoderError("Unknown error in geocoder" + e.getMessage(), null);
        }

        return retList;
    }

    public static LocationInfo getLatLngBoundsFromAddress(String addressName, String languageCode) {

        if (Looper.getMainLooper().equals(Looper.myLooper())) {
            throw new IllegalStateException("Cannot run this method from UI thread");
        }

        try {
            addressName = URLEncoder.encode(addressName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new GeocoderError("Failed encoding place", e);
        }

        String address = "http://maps.googleapis.com/maps/api/geocode/json?address=" + addressName + "&sensor=false&language=" + languageCode;
        if (DEBUG_PRINT) {
            Log.d(TAG, "Will request: " + address);
        }

        OkHttpClient client = new OkHttpClient();
        final Call call = client.newCall(new Request.Builder().get().url(HttpUrl.parse(address)).build());

        LocationInfo locationInfo = null;

        try {
            final Response response = call.execute();
            String json = response.body().string();

            if (DEBUG_PRINT) {
                Log.d(TAG, json);
            }

            JSONObject jsonObject = new JSONObject(json);

            Double neLat = 0.0, neLng = 0.0, swLat = 0.0, swLng = 0.0, lat = 0.0, lng = 0.0;
            boolean found = false;
            LatLngBounds bounds = null;
            LatLngBounds viewport = null;

            if ("OK".equalsIgnoreCase(jsonObject.getString("status"))) {
                JSONArray results = jsonObject.getJSONArray("results");
                if (results.length() > 0) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject result = results.getJSONObject(i);

                        if (result.has("geometry")) {
                            JSONObject geometry = result.getJSONObject("geometry");

                            if (geometry.has("viewport")) {
                                JSONObject viewPortJson = geometry.getJSONObject("viewport");

                                if (viewPortJson.has("northeast")) {
                                    JSONObject northeast = viewPortJson.getJSONObject("northeast");
                                    neLat = northeast.getDouble("lat");
                                    neLng = northeast.getDouble("lng");
                                }

                                if (viewPortJson.has("southwest")) {
                                    JSONObject southwest = viewPortJson.getJSONObject("southwest");
                                    swLat = southwest.getDouble("lat");
                                    swLng = southwest.getDouble("lng");
                                }

                                viewport = new LatLngBounds(new LatLng(swLat, swLng), new LatLng(neLat, neLng));
                            }

                            if (geometry.has("location")) {
                                JSONObject location = geometry.getJSONObject("location");
                                lat = location.getDouble("lat");
                                lng = location.getDouble("lng");
                            }

                            if (geometry.has("bounds")) {
                                JSONObject regionJson = geometry.getJSONObject("bounds");

                                if (regionJson.has("northeast")) {
                                    JSONObject northeast = regionJson.getJSONObject("northeast");
                                    neLat = northeast.getDouble("lat");
                                    neLng = northeast.getDouble("lng");
                                }

                                if (regionJson.has("southwest")) {
                                    JSONObject southwest = regionJson.getJSONObject("southwest");
                                    swLat = southwest.getDouble("lat");
                                    swLng = southwest.getDouble("lng");
                                }
                                bounds = new LatLngBounds(new LatLng(swLat, swLng), new LatLng(neLat, neLng));
                            }
                            found = true;
                        }

                        if (found) {
                            break;
                        }
                    }
                }

                if (null == viewport) {
                    if (null != bounds) {
                        viewport = bounds;
                    } else {
                        throw new GeocoderError("Invalid geocoder response? Or did not process them all!");
                    }
                }

                locationInfo = new LocationInfo(viewport, new LatLng(lat, lng), bounds);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error calling Google geocode webservice.", e);
            throw new GeocoderError("Error calling Google geocode webservice. " + e.getMessage());
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing Google geocode webservice response.", e);
            throw new GeocoderError("Error parsing Google geocode webservice response. " + e.getMessage());
        }

        return locationInfo;
    }

    public static class LocationInfo {
        private LatLngBounds latLngBounds;
        private LatLngBounds latLngViewPort;
        private LatLng       latLng;

        public LocationInfo(@NonNull LatLngBounds viewPort, LatLng latLng, @Nullable LatLngBounds bounds) {
            this.latLngBounds = bounds;
            this.latLng = latLng;
        }

        @Nullable
        public LatLngBounds getLatLngBounds() {
            return latLngBounds;
        }

        @NonNull
        public LatLngBounds getLatLngViewPort() {
            return latLngViewPort;
        }

        public LatLng getLatLng() {
            return latLng;
        }
    }
}
