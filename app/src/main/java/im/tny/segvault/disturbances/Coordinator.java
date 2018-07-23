package im.tny.segvault.disturbances;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import im.tny.segvault.disturbances.model.Trip;
import im.tny.segvault.disturbances.ui.activity.TripCorrectionActivity;
import im.tny.segvault.s2ls.InNetworkState;
import im.tny.segvault.s2ls.NearNetworkState;
import im.tny.segvault.s2ls.OffNetworkState;
import im.tny.segvault.s2ls.Path;
import im.tny.segvault.s2ls.S2LS;
import im.tny.segvault.s2ls.State;
import im.tny.segvault.s2ls.routing.ChangeLineStep;
import im.tny.segvault.s2ls.routing.EnterStep;
import im.tny.segvault.s2ls.routing.ExitStep;
import im.tny.segvault.s2ls.routing.Route;
import im.tny.segvault.s2ls.routing.Step;
import im.tny.segvault.s2ls.wifi.BSSID;
import im.tny.segvault.s2ls.wifi.WiFiLocator;
import im.tny.segvault.subway.Network;
import im.tny.segvault.subway.Station;
import im.tny.segvault.subway.Stop;

import static android.content.Context.MODE_PRIVATE;

public class Coordinator implements MapManager.OnLoadListener {
    private static Coordinator singleton;

    public static Coordinator get(Context context) {
        if (singleton == null) {
            singleton = new Coordinator(context);
        }
        return singleton;
    }

    private Context context;
    private MainService mainService;
    private final Object lock = new Object();

    private PairManager pairManager;
    private Synchronizer synchronizer;
    private MapManager mapManager;
    private Map<String, S2LS> locServices = new HashMap<>();
    private WiFiChecker wiFiChecker;
    private LineStatusCache lineStatusCache;
    private StatsCache statsCache;
    private Random random = new Random();

    private Coordinator(Context context) {
        this.context = context.getApplicationContext();

        pairManager = new PairManager(this.context);
        //pairManager.unpair();
        API.getInstance().setPairManager(pairManager);
        if (!pairManager.isPaired()) {
            pairManager.pairAsync();
        }

        synchronizer = new Synchronizer(this.context);

        mapManager = new MapManager(this.context);
        mapManager.setOnLoadListener(this);
        mapManager.setOnUpdateListener(new MapManager.OnUpdateListener() {
            @Override
            public void onNetworkUpdated(Network network) {
                if (statsCache != null) {
                    statsCache.clear();
                }
            }
        });

        lineStatusCache = new LineStatusCache(this.context);
        statsCache = new StatsCache(this.context);

        wiFiChecker = new WiFiChecker(this.context);
        wiFiChecker.setScanInterval(10000);

        createNotificationChannels();
        reloadFCMsubscriptions();

        OurJobCreator.scheduleAllJobs();
    }

    public Synchronizer getSynchronizer() {
        return synchronizer;
    }

    public MapManager getMapManager() {
        return mapManager;
    }

    public LineStatusCache getLineStatusCache() {
        return lineStatusCache;
    }

    public StatsCache getStatsCache() {
        return statsCache;
    }

    public S2LS getS2LS(String networkId) {
        synchronized (lock) {
            return locServices.get(networkId);
        }
    }

    public S2LS getS2LS(Network network) {
        return getS2LS(network.getId());
    }

    public WiFiChecker getWiFiChecker() {
        return wiFiChecker;
    }

    public Random getRandom() {
        return random;
    }

    public void registerMainService(MainService mainService) {
        this.mainService = mainService;
        propagateMainServiceReference();
    }

    public void unregisterMainService() {
        this.mainService = null;
        propagateMainServiceReference();
    }

    private void propagateMainServiceReference() {
        for (S2LS s2ls : locServices.values()) {
            S2LS.EventListener el = s2ls.getEventListener();
            if (el instanceof S2LSChangeListener) {
                ((S2LSChangeListener) el).setMainService(mainService);
            }
        }
    }

    @Override
    public void onNetworkLoaded(Network network) {
        synchronized (lock) {
            if(Looper.myLooper() == null) {
                Looper.prepare();
            }
            S2LS loc = new S2LS(network, new S2LSChangeListener(context));
            locServices.put(network.getId(), loc);
            WiFiLocator wl = new WiFiLocator(network);
            wiFiChecker.setLocatorForNetwork(network, wl);
            loc.addNetworkDetector(wl);
            loc.addProximityDetector(wl);
            loc.addLocator(wl);
        }
    }

    public static final String NOTIF_CHANNEL_DISTURBANCES_ID = "notif.disturbances";
    public static final String NOTIF_CHANNEL_ANNOUNCEMENTS_ID = "notif.announcements";
    public static final String NOTIF_CHANNEL_REALTIME_ID = "notif.realtime";
    public static final String NOTIF_CHANNEL_REALTIME_HIGH_ID = "notif.realtime.high";
    public static final String NOTIF_CHANNEL_BACKGROUND_ID = "notif.background";

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            if (notificationManager == null) {
                return;
            }

            if (notificationManager.getNotificationChannel("fcm_fallback_notification_channel") == null) {
                String channelName = context.getString(R.string.fcm_fallback_notification_channel_label);
                NotificationChannel channel = new NotificationChannel("fcm_fallback_notification_channel", channelName, NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }

            NotificationChannel channel = new NotificationChannel(NOTIF_CHANNEL_DISTURBANCES_ID, "Disturbances", NotificationManager.IMPORTANCE_HIGH);
            channel.setShowBadge(true);
            notificationManager.createNotificationChannel(channel);

            channel = new NotificationChannel(NOTIF_CHANNEL_ANNOUNCEMENTS_ID, "Announcements", NotificationManager.IMPORTANCE_HIGH);
            channel.setShowBadge(true);
            notificationManager.createNotificationChannel(channel);

            channel = new NotificationChannel(NOTIF_CHANNEL_REALTIME_ID, "Real-time", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);

            channel = new NotificationChannel(NOTIF_CHANNEL_REALTIME_HIGH_ID, "Real-time important", NotificationManager.IMPORTANCE_HIGH);
            channel.setShowBadge(false);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 100, 100, 150, 150, 200});
            notificationManager.createNotificationChannel(channel);

            channel = new NotificationChannel(NOTIF_CHANNEL_BACKGROUND_ID, "Background", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void reloadFCMsubscriptions() {
        FirebaseMessaging fcm = FirebaseMessaging.getInstance();
        SharedPreferences sharedPref = context.getSharedPreferences("notifsettings", MODE_PRIVATE);
        Set<String> linePref = sharedPref.getStringSet(PreferenceNames.NotifsLines, null);
        if (linePref != null && linePref.size() != 0) {
            fcm.subscribeToTopic("disturbances");
            if (BuildConfig.DEBUG) {
                fcm.subscribeToTopic("disturbances-debug");
            }
        } else {
            fcm.unsubscribeFromTopic("disturbances");
            fcm.unsubscribeFromTopic("disturbances-debug");
        }

        Set<String> sourcePref = sharedPref.getStringSet(PreferenceNames.AnnouncementSources, null);

        for (Announcement.Source possibleSource : Announcement.getSources()) {
            if (sourcePref != null && sourcePref.contains(possibleSource.id)) {
                fcm.subscribeToTopic("announcements-" + possibleSource.id);
                if (BuildConfig.DEBUG) {
                    fcm.subscribeToTopic("announcements-debug-" + possibleSource.id);
                }
            } else {
                fcm.unsubscribeFromTopic("announcements-" + possibleSource.id);
                fcm.unsubscribeFromTopic("announcements-debug-" + possibleSource.id);
            }
        }
    }

    public static final String ACTION_CACHE_EXTRAS_PROGRESS = "im.tny.segvault.disturbances.action.cacheextras.progress";
    public static final String EXTRA_CACHE_EXTRAS_PROGRESS_CURRENT = "im.tny.segvault.disturbances.extra.cacheextras.progress.current";
    public static final String EXTRA_CACHE_EXTRAS_PROGRESS_TOTAL = "im.tny.segvault.disturbances.extra.cacheextras.progress.total";
    public static final String ACTION_CACHE_EXTRAS_FINISHED = "im.tny.segvault.disturbances.action.cacheextras.finished";
    public static final String EXTRA_CACHE_EXTRAS_FINISHED = "im.tny.segvault.disturbances.extra.cacheextras.finished";

    public void cacheAllExtras(String... network_ids) {
        ExtraContentCache.clearAllCachedExtras(context);
        for (String id : network_ids) {
            Network network = mapManager.getNetwork(id);
            if (network == null) {
                continue;
            }
            ExtraContentCache.cacheAllExtras(context, new ExtraContentCache.OnCacheAllListener() {
                @Override
                public void onSuccess() {
                    Intent intent = new Intent(ACTION_CACHE_EXTRAS_FINISHED);
                    intent.putExtra(EXTRA_CACHE_EXTRAS_FINISHED, true);
                    LocalBroadcastManager bm = LocalBroadcastManager.getInstance(context);
                    bm.sendBroadcast(intent);
                }

                @Override
                public void onProgress(int current, int total) {
                    Intent intent = new Intent(ACTION_CACHE_EXTRAS_PROGRESS);
                    intent.putExtra(EXTRA_CACHE_EXTRAS_PROGRESS_CURRENT, current);
                    intent.putExtra(EXTRA_CACHE_EXTRAS_PROGRESS_TOTAL, total);
                    LocalBroadcastManager bm = LocalBroadcastManager.getInstance(context);
                    bm.sendBroadcast(intent);
                }

                @Override
                public void onFailure() {
                    Intent intent = new Intent(ACTION_CACHE_EXTRAS_FINISHED);
                    intent.putExtra(EXTRA_CACHE_EXTRAS_FINISHED, false);
                    LocalBroadcastManager bm = LocalBroadcastManager.getInstance(context);
                    bm.sendBroadcast(intent);
                }
            }, network);
        }
    }

    public List<ScanResult> getLastWiFiScanResults() {
        return wiFiChecker.getLastScanResults();
    }

    public void mockLocation(Station station) {
        if (BuildConfig.DEBUG && station.getStops().size() > 0) {
            List<BSSID> bssids = new ArrayList<>();
            for (Stop s : station.getStops()) {
                bssids.addAll(WiFiLocator.getBSSIDsForStop(s));
            }
            wiFiChecker.updateBSSIDsDebug(bssids);
        }
    }
}
