package app.wi_fiheatmapper;

import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

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
    private final SurveyingService.SurveyingServiceConnection mSurveyingServiceConnection
            = new SurveyingService.SurveyingServiceConnection(new SurveyingServiceListener());
    private final Map<Location, Circle> mCirclesMap = new HashMap<>();
    private GoogleMap mMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(new MapReadyCallback());


        if (savedInstanceState == null) {
            SsidPickerDialog dialog = SsidPickerDialog.newInstance(new SsidSelectedCallback());
            dialog.show(getSupportFragmentManager(), SsidPickerDialog.TAG);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        startService(new Intent(this, SurveyingService.class));

        mSurveyingServiceConnection.bound(this);
    }

    @Override
    public void onStop() {
        super.onStop();

        mSurveyingServiceConnection.unbound(this);

        if (isFinishing()) {
            stopService(new Intent(this, SurveyingService.class));
        }
    }

    private void showLastLocation() {
        if (mSurveyingServiceConnection.getService() != null) {
            Location loc = mSurveyingServiceConnection.getService().getLastLocation();
            showLocation(loc);
        }
    }

    private void showLocation(Location loc) {
        if (loc != null && mMap != null) {
            LatLng latLng = locationToLatLng(loc);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 20));
        }
    }

    @NonNull
    private LatLng locationToLatLng(Location loc) {
        return new LatLng(loc.getLatitude(), loc.getLongitude());
    }

    private class SurveyingServiceListener implements SurveyingService.ServiceListener {
        @Override
        public void onScanCompleted(List<ScanResult> scanResults) {
        }

        @Override
        public void onHeatmapDataUpdated(Map<Location, Integer> heatmapData) {
            updateLegend(heatmapData.values());

            if (heatmapData.isEmpty()) return;

            int min = Collections.max(heatmapData.values());
            int max = Collections.min(heatmapData.values());

            for (Map.Entry<Location, Integer> entry : heatmapData.entrySet()) {
                Location loc = entry.getKey();
                Circle circle = mCirclesMap.get(loc);
                if (circle == null) {
                    CircleOptions circleOptions = new CircleOptions().center(locationToLatLng(loc))
                                                                     .radius(getResources().getInteger(R.integer.diameter) / 2d)
                                                                     .strokeWidth(0);
                    circle = mMap.addCircle(circleOptions);
                    mCirclesMap.put(loc, circle);
                }

                // 0 = full signal
                // 1 = no signal
                double signalLoss;
                if (max - min == 0) {
                    signalLoss = 0.5;
                } else {
                    signalLoss = (entry.getValue() - min) / (double) (max - min);
                }
                int color = generateColor(signalLoss);
                if (circle.getFillColor() != color) {
                    circle.setFillColor(color);
                }
            }
        }

        private void updateLegend(Collection<Integer> signalValues) {
            View legendLayout = findViewById(R.id.legend_layout);

            if (signalValues.isEmpty()) {
                if (legendLayout != null) {
                    legendLayout.setVisibility(View.GONE);
                }
                return;
            }

            int min = Collections.max(signalValues);
            int max = Collections.min(signalValues);

            double legendCount = LEGEND_LABEL_VIEWS.length;
            if (legendLayout != null) {
                legendLayout.setVisibility(View.VISIBLE);
                for (int i = 0; i < legendCount; i++) {
                    int signalStrength = (int) ((max - min) * i / (legendCount - 1)) + min;
                    int color = generateColor(i / (legendCount - 1));

                    TextView labelTv = (TextView) legendLayout.findViewById(LEGEND_LABEL_VIEWS[i]);
                    if (labelTv != null) {
                        String label = String.format(getString(R.string.signal_strength), signalStrength);
                        labelTv.setText(label);
                    }

                    View colorView = legendLayout.findViewById(LEGEND_COLOR_VIEWS[i]);
                    if (colorView != null) colorView.setBackgroundColor(color);
                }
            }
        }

        private int generateColor(double signalLoss) {
            int red = 0xFF;
            int green = 0xFF;
            if (signalLoss >= 0.5) {
                // to half add green to make orange
                green = (int) ((1 - signalLoss) * 0xFF / 0.5);
            } else {    // signalLoss < 0.5
                // from half substract red to make green
                red = (int) ((signalLoss) * 0xFF / 0.5);
            }

            return Color.rgb(red, green, 0);
        }

        @Override
        public void onLocationUpdated(Location location) {
            showLocation(location);
        }
    }

    private class SsidSelectedCallback implements SsidPickerDialog.DialogCallback {
        @Override
        public void onSsidSelected(String ssid) {
            if (mSurveyingServiceConnection.getService() != null) {
                mSurveyingServiceConnection.getService().surveySsid(ssid);
            }
        }
    }

    private class MapReadyCallback implements com.google.android.gms.maps.OnMapReadyCallback {
        @Override
        public void onMapReady(GoogleMap googleMap) {
            mMap = googleMap;

            mMap.setMyLocationEnabled(true);

            showLastLocation();
        }
    }
}
