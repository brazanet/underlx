package im.tny.segvault.disturbances.ui.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CollapsingToolbarLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.Toolbar;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import im.tny.segvault.disturbances.Coordinator;
import im.tny.segvault.disturbances.LineStatusCache;
import im.tny.segvault.disturbances.MapManager;
import im.tny.segvault.disturbances.R;
import im.tny.segvault.disturbances.ui.fragment.top.RouteFragment;
import im.tny.segvault.disturbances.Util;
import im.tny.segvault.subway.Connection;
import im.tny.segvault.subway.Line;
import im.tny.segvault.subway.Network;
import im.tny.segvault.subway.Station;
import im.tny.segvault.subway.Stop;

public class LineActivity extends TopActivity {

    private String networkId;
    private String lineId;

    private LinearLayout lineIconsLayout;
    private LinearLayout disturbancesWarningLayout;
    private LinearLayout lineLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            networkId = getIntent().getStringExtra(EXTRA_NETWORK_ID);
            lineId = getIntent().getStringExtra(EXTRA_LINE_ID);
        } else {
            networkId = savedInstanceState.getString(STATE_NETWORK_ID);
            lineId = savedInstanceState.getString(STATE_LINE_ID);
        }

        setContentView(R.layout.activity_line);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.hide();
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        lineIconsLayout = (LinearLayout) findViewById(R.id.line_icons_layout);
        disturbancesWarningLayout = (LinearLayout) findViewById(R.id.disturbances_warning_layout);
        lineLayout = (LinearLayout) findViewById(R.id.line_layout);

        Network net = Coordinator.get(this).getMapManager().getNetwork(networkId);
        Line line = net.getLine(lineId);

        String title = String.format(getString(R.string.act_line_title), Util.getLineNames(LineActivity.this, line)[0]);
        setTitle(title);
        getSupportActionBar().setTitle(title);
        AppBarLayout abl = (AppBarLayout) findViewById(R.id.app_bar);
        final CollapsingToolbarLayout ctl = (CollapsingToolbarLayout) findViewById(R.id.toolbar_layout);
        ctl.setTitle(title);

        int color = line.getColor();
        ctl.setContentScrimColor(color);
        ctl.setStatusBarScrimColor(color);
        abl.setBackgroundColor(color);

        Drawable drawable = ContextCompat.getDrawable(LineActivity.this, Util.getDrawableResourceIdForLineId(line.getId()));
        drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);

        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 35, getResources().getDisplayMetrics());
        FrameLayout iconFrame = new FrameLayout(LineActivity.this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(height, height);
        int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            params.setMarginEnd(margin);
        }
        params.setMargins(0, 0, margin, 0);
        iconFrame.setLayoutParams(params);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            iconFrame.setBackgroundDrawable(drawable);
        } else {
            iconFrame.setBackground(drawable);
        }
        lineIconsLayout.addView(iconFrame);

        abl.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
            @Override
            public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
                if (ctl.getHeight() + verticalOffset < 2.5 * ViewCompat.getMinimumHeight(ctl)) {
                    lineIconsLayout.animate().alpha(0);
                } else {
                    lineIconsLayout.animate().alpha(1);
                }
            }
        });

        Map<String, LineStatusCache.Status> statuses = Coordinator.get(this).getLineStatusCache().getLineStatus();
        if (statuses.get(line.getId()) != null &&
                statuses.get(line.getId()).down) {
            disturbancesWarningLayout.setVisibility(View.VISIBLE);
        } else {
            disturbancesWarningLayout.setVisibility(View.GONE);
        }

        LinearLayout closedLayout = (LinearLayout) findViewById(R.id.closed_info_layout);
        if (line.isExceptionallyClosed(new Date())) {
            TextView closedView = (TextView) findViewById(R.id.closed_info_view);
            Formatter f = new Formatter();
            DateUtils.formatDateRange(LineActivity.this, f, line.getNextOpenTime(), line.getNextOpenTime(), DateUtils.FORMAT_SHOW_TIME, net.getTimezone().getID());
            closedView.setText(String.format(getString(R.string.act_line_closed_schedule), f.toString()));


            closedLayout.setVisibility(View.VISIBLE);
        } else {
            closedLayout.setVisibility(View.GONE);
        }

        populateLineView(LineActivity.this, getLayoutInflater(), line, lineLayout);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static void populateLineView(final Context context, final LayoutInflater inflater, final Line line, ViewGroup root) {
        root.removeAllViews();

        List<Station> stations = line.getStationsInOrder();

        int lineColor = line.getColor();

        for (int i = 0; i < stations.size(); i++) {
            final Station station = stations.get(i);

            View stepview = inflater.inflate(R.layout.path_station, root, false);

            TextView timeView = (TextView) stepview.findViewById(R.id.time_view);
            timeView.setVisibility(View.INVISIBLE);

            FrameLayout prevLineStripeLayout = (FrameLayout) stepview.findViewById(R.id.prev_line_stripe_layout);
            FrameLayout nextLineStripeLayout = (FrameLayout) stepview.findViewById(R.id.next_line_stripe_layout);
            FrameLayout leftLineStripeLayout = (FrameLayout) stepview.findViewById(R.id.left_line_stripe_layout);
            FrameLayout rightLineStripeLayout = (FrameLayout) stepview.findViewById(R.id.right_line_stripe_layout);

            if (i == 0) {
                prevLineStripeLayout.setVisibility(View.GONE);
                nextLineStripeLayout.setBackgroundColor(lineColor);
            } else if (i == stations.size() - 1) {
                prevLineStripeLayout.setBackgroundColor(lineColor);
                nextLineStripeLayout.setVisibility(View.GONE);
            } else {
                prevLineStripeLayout.setBackgroundColor(lineColor);
                nextLineStripeLayout.setBackgroundColor(lineColor);
            }

            if (station.getLines().size() > 1) {
                for (Stop stop : station.getStops()) {
                    if (stop.getLine() != line) {
                        rightLineStripeLayout.setVisibility(View.VISIBLE);

                        GradientDrawable gd = new GradientDrawable(
                                GradientDrawable.Orientation.RIGHT_LEFT,
                                new int[]{0, stop.getLine().getColor()});
                        gd.setCornerRadius(0f);

                        if (Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                            rightLineStripeLayout.setBackgroundDrawable(gd);
                        } else {
                            rightLineStripeLayout.setBackground(gd);
                        }

                        if (stop.getLine().outDegreeOf(stop) > 1) {
                            leftLineStripeLayout.setVisibility(View.VISIBLE);
                            gd = new GradientDrawable(
                                    GradientDrawable.Orientation.LEFT_RIGHT,
                                    new int[]{0, stop.getLine().getColor()});
                            gd.setCornerRadius(0f);

                            if (Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                                leftLineStripeLayout.setBackgroundDrawable(gd);
                            } else {
                                leftLineStripeLayout.setBackground(gd);
                            }
                        }
                        break;
                    }
                }
            }

            stepview.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent intent = new Intent(context, StationActivity.class);
                    intent.putExtra(StationActivity.EXTRA_STATION_ID, station.getId());
                    intent.putExtra(StationActivity.EXTRA_NETWORK_ID, station.getNetwork().getId());
                    context.startActivity(intent);
                }
            });

            RouteFragment.populateStationView(context, station, stepview, true, false);
            root.addView(stepview);
        }
    }

    public static final String STATE_LINE_ID = "lineId";
    public static final String STATE_NETWORK_ID = "networkId";

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putString(STATE_LINE_ID, lineId);
        savedInstanceState.putString(STATE_NETWORK_ID, networkId);

        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);
    }

    public static final String EXTRA_LINE_ID = "im.tny.segvault.disturbances.extra.LineActivity.lineid";
    public static final String EXTRA_NETWORK_ID = "im.tny.segvault.disturbances.extra.LineActivity.networkid";

}
