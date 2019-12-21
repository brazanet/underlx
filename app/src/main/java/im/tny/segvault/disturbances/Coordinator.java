package im.tny.segvault.disturbances;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.ScanResult;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.room.Room;

import com.google.firebase.messaging.FirebaseMessaging;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import im.tny.segvault.disturbances.database.AppDatabase;
import im.tny.segvault.disturbances.database.StationPreference;
import im.tny.segvault.disturbances.model.NotificationRule;
import im.tny.segvault.disturbances.model.RStation;
import im.tny.segvault.disturbances.model.StationUse;
import im.tny.segvault.disturbances.model.Trip;
import im.tny.segvault.s2ls.S2LS;
import im.tny.segvault.s2ls.wifi.BSSID;
import im.tny.segvault.s2ls.wifi.WiFiLocator;
import im.tny.segvault.subway.Network;
import im.tny.segvault.subway.Station;
import im.tny.segvault.subway.Stop;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.exceptions.RealmFileException;

import static android.content.Context.MODE_PRIVATE;

public class Coordinator implements MapManager.OnLoadListener {
    // there's no risk of leaking the context because we're doing context.getApplicationContext()
    // see https://stackoverflow.com/questions/39840818/android-googles-contradiction-on-singleton-pattern/39841446#39841446
    @SuppressLint("StaticFieldLeak")
    private static Coordinator singleton;

    public static synchronized Coordinator get(Context context) {
        if (singleton == null) {
            context = LocaleUtil.updateResources(context);
            singleton = new Coordinator(context.getApplicationContext());

            // force maps to load so onNetworkLoaded is called and the WiFiChecker, etc. is attached to the network
            singleton.getMapManager().getNetworks();
        }
        return singleton;
    }

    private Context context;
    private MainService mainService;
    private final Object lock = new Object();

    private AppDatabase db;
    private PairManager pairManager;
    private Synchronizer synchronizer;
    private MapManager mapManager;
    private Map<String, S2LS> locServices = new HashMap<>();
    private WiFiChecker wiFiChecker;
    private LineStatusCache lineStatusCache;
    private CacheManager cacheManager;
    private MqttManager mqttManager;
    private Random random = new Random();

    private Coordinator(Context context) {
        this.context = context.getApplicationContext();

        db = Room.databaseBuilder(context, AppDatabase.class, "underlx")
                .fallbackToDestructiveMigration()
                .build();

        pairManager = new PairManager(this.context);
        //pairManager.unpair();
        API.getInstance().setPairManager(pairManager);
        if (!pairManager.isPaired()) {
            pairManager.pairAsync();
        }

        synchronizer = new Synchronizer(this.context);

        mapManager = new MapManager(this.context);
        mapManager.setOnLoadListener(this);
        mapManager.setOnUpdateListener(network -> {

        });

        lineStatusCache = new LineStatusCache(this.context);
        cacheManager = new CacheManager(this.context);
        mqttManager = new MqttManager(this.context);

        wiFiChecker = new WiFiChecker(this.context);
        wiFiChecker.setScanInterval(10000);

        createNotificationChannels();
        reloadFCMsubscriptions();

        OurJobCreator.scheduleAllJobs();

        SharedPreferences sharedPref = this.context.getSharedPreferences("settings", MODE_PRIVATE);
        boolean migratedRealmToRoom = sharedPref.getBoolean("fuse_migrated_realm_to_room", false);

        if (!migratedRealmToRoom) {
            new RealmToRoomTask(this.context).executeOnExecutor(Util.LARGE_STACK_THREAD_POOL_EXECUTOR);
        }
    }

    public AppDatabase getDB() {
        return db;
    }

    public PairManager getPairManager() {
        return pairManager;
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

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public MqttManager getMqttManager() {
        return mqttManager;
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
        FirebaseMessaging fcm;
        try {
            fcm = FirebaseMessaging.getInstance();
        } catch (IllegalStateException ex) {
            // it doesn't say anywhere in the docs why this would happen,
            // but people all over the world suspect it's because it takes some time for FCM to acquire the tokens it needs
            return;
        }

        fcm.subscribeToTopic("broadcasts");
        if (BuildConfig.DEBUG) {
            fcm.subscribeToTopic("broadcasts-debug");
        }

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

        if (pairManager.isPaired()) {
            String topicName = String.format("pair-%s", pairManager.getPairKey());
            fcm.subscribeToTopic(topicName);
            if (BuildConfig.DEBUG) {
                fcm.subscribeToTopic(topicName + "-debug");
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

    //region Navigation drawer images
    private static final int[] navImages = {
            R.drawable.nav_1,
            R.drawable.nav_2,
            R.drawable.nav_3,
            R.drawable.nav_4,
            R.drawable.nav_5,
            R.drawable.nav_6};
    private static final int[] navTextShadowColor = {
            -1,
            -2,
            -1,
            Color.BLACK,
            Color.BLACK,
            -2
    };
    private static final String[] navImageCredits = {
            "Jaime Silva",
            "Carlos Fonseca",
            "arcticpenguin",
            "Barry J Dillon",
            "Roberta R.",
            "Javier Gonzalez"
    };

    private int lastSelectedNavImageOffset = 0;

    public int getNavImageResource() {
        lastSelectedNavImageOffset = (int) ((new Date().getTime() / TimeUnit.HOURS.toMillis(10)) % navImages.length);
        return navImages[lastSelectedNavImageOffset];
    }

    public int getNavTextShadowColor() {
        switch (navTextShadowColor[lastSelectedNavImageOffset]) {
            case -1:
                return context.getResources().getColor(R.color.colorAccent);
            case -2:
                return context.getResources().getColor(R.color.colorPrimary);
            default:
                return navTextShadowColor[lastSelectedNavImageOffset];
        }
    }

    public String getNavImageCredits() {
        return navImageCredits[lastSelectedNavImageOffset];
    }
    //endregion

    private static class RealmToRoomTask extends AsyncTask<Void, Void, Boolean> {
        private WeakReference<Context> contextRef;

        RealmToRoomTask(Context context) {
            this.contextRef = new WeakReference<>(context);
        }

        private static void initRealm(Context context) {
            // Initialize Realm. Should only be done once when the application starts.
            Realm.init(context);
            RealmConfiguration config = new RealmConfiguration.Builder()
                    .schemaVersion(8) // Must be bumped when the schema changes
                    .migration(new Application.MyMigration())
                    .build();
            Realm.setDefaultConfiguration(config);
        }

        private static Realm getDefaultRealmInstance(Context context) {
            Realm realm;
            try {
                realm = Realm.getDefaultInstance();
            } catch (IllegalStateException e) {
                initRealm(context);
                realm = Realm.getDefaultInstance();
            } catch (RealmFileException e) {
                // happens when the DB is corrupted
                initRealm(context);
                realm = Realm.getDefaultInstance();
            }
            return realm;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            Context context = contextRef.get();
            if (context == null) {
                return false;
            }

            AppDatabase db = Coordinator.get(context).getDB();

            boolean successful = false;
            Realm realm = getDefaultRealmInstance(context);
            try {
                db.runInTransaction(() -> {
                    for (Trip trip : realm.where(Trip.class).findAll()) {
                        im.tny.segvault.disturbances.database.Trip newTrip = new im.tny.segvault.disturbances.database.Trip();
                        newTrip.id = trip.getId();
                        newTrip.syncFailures = trip.isFailedToSync() ? 5 : 0;
                        newTrip.synced = trip.isSynced();
                        newTrip.userConfirmed = trip.isUserConfirmed();

                        db.tripDao().insertAll(newTrip);

                        int order = 0;
                        for (StationUse use : trip.getPath()) {
                            im.tny.segvault.disturbances.database.StationUse newUse = new im.tny.segvault.disturbances.database.StationUse();
                            newUse.stationID = use.getStation().getId();
                            newUse.entryDate = use.getEntryDate();
                            newUse.leaveDate = use.getLeaveDate();
                            newUse.sourceLine = use.getSourceLine();
                            newUse.targetLine = use.getTargetLine();
                            switch (use.getType()) {
                                case NETWORK_ENTRY:
                                    newUse.type = im.tny.segvault.disturbances.database.StationUse.UseType.NETWORK_ENTRY;
                                    break;
                                case NETWORK_EXIT:
                                    newUse.type = im.tny.segvault.disturbances.database.StationUse.UseType.NETWORK_EXIT;
                                    break;
                                case INTERCHANGE:
                                    newUse.type = im.tny.segvault.disturbances.database.StationUse.UseType.INTERCHANGE;
                                    break;
                                case GONE_THROUGH:
                                    newUse.type = im.tny.segvault.disturbances.database.StationUse.UseType.GONE_THROUGH;
                                    break;
                                case VISIT:
                                    newUse.type = im.tny.segvault.disturbances.database.StationUse.UseType.VISIT;
                                    break;
                            }
                            newUse.manualEntry = use.isManualEntry();
                            newUse.order = order++;
                            newUse.tripID = newTrip.id;

                            db.stationUseDao().insertAll(newUse);
                        }
                    }

                    for (NotificationRule rule : realm.where(NotificationRule.class).findAll()) {
                        im.tny.segvault.disturbances.database.NotificationRule newRule = new im.tny.segvault.disturbances.database.NotificationRule();
                        newRule.id = rule.getId();
                        newRule.enabled = rule.isEnabled();
                        newRule.name = rule.getName();
                        newRule.weekDays = new int[rule.getWeekDays().size()];
                        for (int i = 0; i < rule.getWeekDays().size(); i++)
                            newRule.weekDays[i] = rule.getWeekDays().get(i);
                        newRule.startTime = rule.getStartTime();
                        newRule.endTime = rule.getEndTime();
                        db.notificationRuleDao().insertOrUpdateAll(newRule);
                    }

                    for (RStation station : realm.where(RStation.class).equalTo("favorite", true).findAll()) {
                        StationPreference sp = new StationPreference();
                        sp.networkID = station.getNetwork();
                        sp.stationID = station.getId();
                        sp.favorite = true;
                        db.stationPreferenceDao().insertOrUpdateAll(sp);
                    }
                });

                SharedPreferences sharedPref = context.getSharedPreferences("settings", MODE_PRIVATE);
                SharedPreferences.Editor e = sharedPref.edit();
                e.putBoolean("fuse_migrated_realm_to_room", true);
                e.apply();
                successful = true;
                Log.i("RealmToRoom", "Going to close and delete realm");
            } catch (Throwable e) {
                Log.w("RealmToRoom", "Migration failed: " + e);
                e.printStackTrace();
                return false;
            } finally {
                realm.close();
                if(successful) {
                    try {
                        Realm.deleteRealm(Realm.getDefaultConfiguration());
                        Log.i("RealmToRoom", "Migrated successfully");
                    } catch(Throwable e) {
                        Log.w("RealmToRoom", "Migrated successfully but couldn't delete realm: " + e);
                    }

                }
            }
            return true;
        }
    }
}
