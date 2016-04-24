package app.wi_fiheatmapper;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.graphics.ColorUtils;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.maps.android.SphericalUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Acitivity show RSSI heatmap of selected network on map.
 */
public class MainActivity extends AppCompatActivity {

    public static final String TAG = MainActivity.class.getSimpleName();

    private static final int[] LEGEND_LABEL_VIEWS = new int[]{R.id.legend_label_1,
                                                              R.id.legend_label_2,
                                                              R.id.legend_label_3,
                                                              R.id.legend_label_4,
                                                              R.id.legend_label_5};
    private static final int[] LEGEND_COLOR_VIEWS = new int[]{R.id.legend_color_1,
                                                              R.id.legend_color_2,
                                                              R.id.legend_color_3,
                                                              R.id.legend_color_4,
                                                              R.id.legend_color_5};

    private static final int MAX_BITMAP_DIMEN = 128;
    private static final float P_EXPONENT = 3f;

    private final SurveyingService.SurveyingServiceConnection mSurveyingServiceConnection
            = new SurveyingService.SurveyingServiceConnection(new SurveyingServiceListener());
    /**
     * Map hold for circles drawed on map for corresponding location.
     */
    private final Map<Location, Circle> mLocToCircleMap = new HashMap<>();

    private GoogleMap mMap;
    private GroundOverlay mGroundOverlay;
    private AsyncTask<Void, Void, Bitmap> mBitmapTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(new MapReadyCallback());

        // Show dialog only if this is new launch.
        if (savedInstanceState == null) {
            SsidPickerDialog dialog = SsidPickerDialog.newInstance(new SsidSelectedCallback());
            dialog.show(getSupportFragmentManager(), SsidPickerDialog.TAG);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // Start service for network surveying....
        startService(new Intent(this, SurveyingService.class));
        // ....and bound to it for getting events.
        mSurveyingServiceConnection.bound(this);
    }

    @Override
    public void onStop() {
        super.onStop();

        mSurveyingServiceConnection.unbound(this);

        // Stop service only if app is terminated.
        if (isFinishing()) stopService(new Intent(this, SurveyingService.class));

        if (mBitmapTask != null) {
            mBitmapTask.cancel(true);
            mBitmapTask = null;
        }
    }

    /**
     * Get and show last known location.
     */
    private void showLastLocation() {
        if (mSurveyingServiceConnection.getService() != null) {
            Location loc = mSurveyingServiceConnection.getService().getLastLocation();
            showLocation(loc, 20);
        }
    }

    /**
     * Show desired location.
     *
     * @param loc  Location to show.
     * @param zoom Zoom level to show.
     */
    private void showLocation(Location loc, float zoom) {
        if (loc != null && mMap != null) {
            LatLng latLng = locationToLatLng(loc);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, zoom));
        }
    }

    /**
     * Draw heatmap on map.
     *
     * @param heatmapData Data for drawing heatmap. RSSI on location.
     */
    private void repaintMap(Map<Location, Integer> heatmapData) {
        // Return if nothing to draw or drawing is in progress.
        if (heatmapData.isEmpty()) return;

        if (mBitmapTask == null) mBitmapTask = new HeatmapToBitmapTask(heatmapData).execute();

        // Mark position where RSSI was measured.
        for (Map.Entry<Location, Integer> entry : heatmapData.entrySet()) {
            if (mLocToCircleMap.get(entry.getKey()) == null) {
                LatLng latLng = locationToLatLng(entry.getKey());
                int color = ColorUtils.setAlphaComponent(Color.BLACK, 128);
                CircleOptions circleOptions = new CircleOptions().center(latLng)
                                                                 .radius(0.1)
                                                                 .strokeWidth(0)
                                                                 .fillColor(color);
                if (mMap != null) {
                    Circle circle = mMap.addCircle(circleOptions);
                    mLocToCircleMap.put(entry.getKey(), circle);
                }
            }
        }
    }

    /**
     * Estimate RSSI on (x, y) coordinates from known points.
     * <p/>
     * http://homel.vsb.cz/~hom50/SLBGEOST/LOD/GS09.HTM<br/>
     * https://en.wikipedia.org/wiki/Inverse_distance_weighting
     *
     * @param knownPoints Map of known points with RSSI value.
     * @param x           X coord of estimating point.
     * @param y           Y coord of estimating point.
     * @return Estimated RSSI.
     */
    private int estimateRssi(Map<Point, Integer> knownPoints, int x, int y) {
        double sum1 = 0, sum2 = 0;
        for (Map.Entry<Point, Integer> entry : knownPoints.entrySet()) {
            // Get int immediately for a little better performance.
            Point knownPoint = entry.getKey();
            int knownRssi = entry.getValue().intValue();

            double distance = Math.sqrt(Math.pow(x - knownPoint.x, 2) + Math.pow(y - knownPoint.y, 2));

            if (distance == 0) return knownRssi;

            double w = 1 / Math.pow(distance, P_EXPONENT);
            sum1 += w * knownRssi;
            sum2 += w;
        }

        return Double.isNaN(sum1 / sum2) ? Integer.MIN_VALUE : (int) (sum1 / sum2);
    }

    /**
     * Compute and show color legend for drawed data.
     * Collected RSSI range is divided on n part and each part have color corresponding to RSSI value.
     *
     * @param rssis Collection of RSSIs.
     */
    private void repaintLegend(Collection<Integer> rssis) {
        View legendLayout = findViewById(R.id.legend_layout);

        // Count only with valid RSSI values.
        Collection<Integer> values = new ArrayList<>(rssis);
        values.removeAll(Collections.singleton(Integer.MIN_VALUE));
        if (values.isEmpty()) {
            // Hide legend of no data available.
            if (legendLayout != null) {
                legendLayout.setVisibility(View.GONE);
            }
            return;
        }

        int maxRssi = Collections.max(values);
        int minRssi = Collections.min(values);

        double legendCount = LEGEND_LABEL_VIEWS.length;
        if (legendLayout != null) {
            legendLayout.setVisibility(View.VISIBLE);
            for (int i = 0; i < legendCount; i++) {
                // Divide RSSI range to parts and get color for this parts.
                int signalStrength = (int) ((minRssi - maxRssi) * i / (legendCount - 1)) + maxRssi;
                int color = generateColor(i / (legendCount - 1));

                View labelTv = legendLayout.findViewById(LEGEND_LABEL_VIEWS[i]);
                if (labelTv instanceof TextView) {
                    String label = String.format(getString(R.string.signal_strength), signalStrength);
                    ((TextView) labelTv).setText(label);
                }

                View colorView = legendLayout.findViewById(LEGEND_COLOR_VIEWS[i]);
                if (colorView != null) colorView.setBackgroundColor(color);
            }
        }
    }

    /**
     * Show info about currently surveyed network.
     *
     * @param surveyedSsid SSID of surveyed network.
     * @param lastRssi     Last RSSI for this network.
     */
    private void repaintSurveyedWiFiInfo(String surveyedSsid, int lastRssi) {
        View legendLayout = findViewById(R.id.legend_layout);
        if (legendLayout != null) {
            View currentWifiTv = legendLayout.findViewById(R.id.legend_current_wifi);
            if (currentWifiTv instanceof TextView) {
                String signalString;
                if (lastRssi == Integer.MIN_VALUE) {
                    signalString = String.format(getString(R.string.network_not_in_range), surveyedSsid);
                } else {
                    signalString = String.format(getString(R.string.current_wifi), surveyedSsid,
                                                 lastRssi);
                }
                ((TextView) currentWifiTv).setText(signalString);
            }
        }
    }

    private int generateColor(double relativeSignal) {
        int red = 0xFF;
        int green = 0xFF;
        if (relativeSignal >= 0.5) {
            // From half substract red from green to make full green.
            red = (int) ((1 - relativeSignal) * 0xFF / 0.5);
        } else {
            // To half of range add green to red to make orange.
            green = (int) ((relativeSignal) * 0xFF / 0.5);
        }

        return Color.rgb(red, green, 0);
    }

    /**
     * Helper to transform {@link Location} to {@link LatLng}.
     *
     * @param location Location to transform
     * @return Transformed location
     */
    @NonNull
    private LatLng locationToLatLng(Location location) {
        return new LatLng(location.getLatitude(), location.getLongitude());
    }

    private class SurveyingServiceListener implements SurveyingService.ServiceListener {
        @Override
        public void onWiFiScanCompleted(List<ScanResult> scanResults) {
        }

        @Override
        public void onHeatmapDataUpdated(Map<Location, Integer> heatmapData) {
            repaintMap(heatmapData);
        }

        @Override
        public void onLastLocationUpdated(Location location) {
            showLocation(location, 20);
        }

        @Override
        public void onSurveyedWiFiUpdated(String surveyedSsid, int rssi) {
            repaintSurveyedWiFiInfo(surveyedSsid, rssi);
        }
    }

    /**
     * Callback for SSID picker dialog.
     */
    private class SsidSelectedCallback implements SsidPickerDialog.DialogCallback {
        @Override
        public void onSsidSelected(String ssid) {
            // Start surveying selected network.
            if (mSurveyingServiceConnection.getService() != null) {
                mSurveyingServiceConnection.getService().surveySsid(ssid);
            }
        }
    }

    private class MapReadyCallback implements com.google.android.gms.maps.OnMapReadyCallback {
        @Override
        public void onMapReady(GoogleMap googleMap) {
            mMap = googleMap;

            showLastLocation();

            mMap.setMyLocationEnabled(true);
        }
    }

    /**
     * Task for asynchronous creating bitmap from heatmap data.
     */
    private class HeatmapToBitmapTask extends AsyncTask<Void, Void, Bitmap> {

        private final Map<Location, Integer> mHeatmapData;
        private LatLngBounds mAreaBounds;

        public HeatmapToBitmapTask(Map<Location, Integer> heatmapData) {
            mHeatmapData = heatmapData;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            // Create bounds around surveyed area.
            LatLngBounds.Builder boundsBuilder = LatLngBounds.builder();
            for (Map.Entry<Location, Integer> entry : mHeatmapData.entrySet()) {
                boundsBuilder.include(locationToLatLng(entry.getKey()));
            }
            mAreaBounds = boundsBuilder.build();

            // Expand bounds for showing heatmap around boundary points.
            mAreaBounds = new LatLngBounds(new LatLng(mAreaBounds.southwest.latitude - 0.0001,
                                                      mAreaBounds.southwest.longitude - 0.0002),
                                           new LatLng(mAreaBounds.northeast.latitude + 0.0001,
                                                      mAreaBounds.northeast.longitude + 0.0002));

            // Dimensions of surveyed area in meters.
            float areaWidth
                    = (float) SphericalUtil.computeDistanceBetween(new LatLng(mAreaBounds.southwest.latitude,
                                                                              mAreaBounds.southwest.longitude),
                                                                   new LatLng(mAreaBounds.southwest.latitude,
                                                                              mAreaBounds.northeast.longitude));
            float areaHeight
                    = (float) SphericalUtil.computeDistanceBetween(new LatLng(mAreaBounds.southwest.latitude,
                                                                              mAreaBounds.southwest.longitude),
                                                                   new LatLng(mAreaBounds.northeast.latitude,
                                                                              mAreaBounds.southwest.longitude));

            // Calculate bitmap dimensions by size of area.
            int bitmapWidth;
            int bitmapHeight;
            if (areaWidth > areaHeight) {
                bitmapWidth = MAX_BITMAP_DIMEN;
                bitmapHeight = (int) (bitmapWidth * (areaHeight / areaWidth));
                // Overlay bitmap dimens should be powers of two.
                if (Integer.highestOneBit(bitmapHeight) != bitmapHeight) {
                    bitmapHeight = Integer.highestOneBit(bitmapHeight) << 1;
                }
            } else {
                bitmapHeight = MAX_BITMAP_DIMEN;
                bitmapWidth = (int) (bitmapHeight * (areaWidth / areaHeight));
                // Overlay bitmap dimens should be powers of two.
                if (Integer.highestOneBit(bitmapWidth) != bitmapWidth) {
                    bitmapWidth = Integer.highestOneBit(bitmapWidth) << 1;
                }
            }

            // Convert locations to points in bitmap.
            // Bitmap have origin in top left, location in bottom left.
            Map<Point, Integer> knownPoints = new HashMap<>(mHeatmapData.size());
            for (Map.Entry<Location, Integer> entry : mHeatmapData.entrySet()) {
                LatLng latLng = locationToLatLng(entry.getKey());
                double x = bitmapWidth * (latLng.longitude - mAreaBounds.southwest.longitude)
                        / (mAreaBounds.northeast.longitude - mAreaBounds.southwest.longitude);
                double y = bitmapHeight * (1 - (latLng.latitude - mAreaBounds.southwest.latitude)
                        / (mAreaBounds.northeast.latitude - mAreaBounds.southwest.latitude));
                knownPoints.put(new Point((int) x, (int) y), entry.getValue());
            }

            int maxRssi = Collections.max(mHeatmapData.values());
            int minRssi = Collections.min(mHeatmapData.values());

            // Bitmap dimension cannot be zero.
            Bitmap bitmap = Bitmap.createBitmap(Math.max(bitmapWidth, 1),
                                                Math.max(bitmapHeight, 1),
                                                Bitmap.Config.ARGB_8888);
            long startMillis = System.currentTimeMillis();
            for (int x = 0; x < bitmap.getWidth(); x++) {
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    int rssi = estimateRssi(knownPoints, x, y);

                    if (rssi == Integer.MIN_VALUE) continue;

                    // Signal strenght relative to range of currently collected RSSIs.
                    // 0 = strongest signal
                    // 1 = weakest signal
                    double relativeSignalStrenght;
                    if (minRssi - maxRssi == 0) {
                        relativeSignalStrenght = 0.5;
                    } else {
                        relativeSignalStrenght = (rssi - maxRssi) / (double) (minRssi - maxRssi);
                    }

                    int color = ColorUtils.setAlphaComponent(generateColor(relativeSignalStrenght),
                                                             128);
                    bitmap.setPixel(x, y, color);
                }

                if (isCancelled()) return bitmap;
            }
            Log.d(TAG, "bitmap created in " + (System.currentTimeMillis() - startMillis) + "ms");

            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            // Add heatmap overlay to map.
            if (mGroundOverlay == null) {
                if (mMap != null) {
                    GroundOverlayOptions overlayOptions = new GroundOverlayOptions()
                            .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                            .positionFromBounds(mAreaBounds)
                            .zIndex(10);
                    mGroundOverlay = mMap.addGroundOverlay(overlayOptions);
                }
            } else {
                mGroundOverlay.setPositionFromBounds(mAreaBounds);
                mGroundOverlay.setImage(BitmapDescriptorFactory.fromBitmap(bitmap));
            }

            repaintLegend(mHeatmapData.values());

            mBitmapTask = null;
            // Start generating new bitmap if data changed from last bitmap. Else wait for new data.
            if (mSurveyingServiceConnection.getService() != null) {
                Map<Location, Integer> heatmapData = mSurveyingServiceConnection.getService().getHeatmapData();
                if (heatmapData.size() != mHeatmapData.size()) {
                    mBitmapTask = new HeatmapToBitmapTask(heatmapData).execute();
                }
            }
        }
    }
}
