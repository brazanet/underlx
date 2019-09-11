package im.tny.segvault.disturbances;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import im.tny.segvault.disturbances.exception.APIException;
import im.tny.segvault.disturbances.model.StationUse;
import im.tny.segvault.disturbances.model.Trip;
import io.realm.Realm;
import io.realm.RealmResults;

/**
 * Created by Gabriel on 26/07/2017.
 */

public class Synchronizer {
    private Context context;

    private LocalBroadcastManager bm;
    private Date lastSync;
    private Object lock = new Object();

    public Synchronizer(Context applicationContext) {
        context = applicationContext;

        IntentFilter filter = new IntentFilter();
        filter.addAction(MainService.ACTION_TRIP_REALM_UPDATED);
        filter.addAction(MainService.ACTION_FEEDBACK_REALM_UPDATED);
        LocalBroadcastManager bm = LocalBroadcastManager.getInstance(context);
        bm.registerReceiver(mBroadcastReceiver, filter);
    }

    public void asyncSync() {
        new SyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void sync() {
        Log.d("sync", "Waiting for lock");
        synchronized (lock) {
            Log.d("sync", "Lock acquired");
            if (!Connectivity.isConnected(context)) {
                Log.d("sync", "Not connected, abort");
                return;
            }
            if(lastSync != null && new Date().getTime() - lastSync.getTime() < TimeUnit.SECONDS.toMillis(2)) {
                Log.d("sync", "Last sync was right now, abort");
                // avoid looping on ACTION_TRIP_REALM_UPDATED broadcasts
                return;
            }
            Realm realm = Application.getDefaultRealmInstance(context);
            realm.executeTransaction(realm1 -> {
                RealmResults<Trip> unsyncedTrips = realm1.where(Trip.class).equalTo("synced", false).findAll();
                for (Trip t : unsyncedTrips) {
                    API.TripRequest request = tripToAPIRequest(t);
                    try {
                        if (t.isSubmitted()) {
                            // submit update, if possible
                            if (t.canBeCorrected()) {
                                API.getInstance().putTrip(request);
                                t.setSynced(true);
                                t.setSubmitted(true);
                            }
                        } else {
                            // submit new
                            API.getInstance().postTrip(request);
                            t.setSynced(true);
                            t.setSubmitted(true);
                        }
                    } catch (APIException e) {
                        t.registerSyncFailure();
                        if(t.isFailedToSync()) {
                            // give up on this one
                            t.setSynced(true);
                        }
                    }
                }

                RealmResults<im.tny.segvault.disturbances.model.Feedback> unsyncedFeedback = realm1.where(im.tny.segvault.disturbances.model.Feedback.class).equalTo("synced", false).findAll();
                for (im.tny.segvault.disturbances.model.Feedback f : unsyncedFeedback) {
                    API.Feedback request = feedbackToAPIRequest(f);
                    try {
                        API.getInstance().postFeedback(request);
                        f.setSynced(true);
                    } catch (APIException e) {
                        e.printStackTrace();
                    }
                }
            });
            realm.close();
            lastSync = new Date();
            Log.d("sync", "Sync done");
        }
    }

    private API.TripRequest tripToAPIRequest(Trip t) {
        API.TripRequest request = new API.TripRequest();
        request.id = t.getId();
        request.userConfirmed = t.isUserConfirmed();
        request.uses = new ArrayList<>(t.getPath().size());
        for (StationUse use : t.getPath()) {
            request.uses.add(stationUseToAPI(use));
        }
        return request;
    }

    private API.StationUse stationUseToAPI(StationUse use) {
        API.StationUse apiUse = new API.StationUse();
        apiUse.station = use.getStation().getId();
        apiUse.entryTime = new long[]{use.getEntryDate().getTime() / 1000, (use.getEntryDate().getTime() % 1000) * 1000000};
        apiUse.leaveTime = new long[]{use.getLeaveDate().getTime() / 1000, (use.getLeaveDate().getTime() % 1000) * 1000000};
        apiUse.type = use.getType().name();
        apiUse.manual = use.isManualEntry();
        if (use.getType() == StationUse.UseType.INTERCHANGE) {
            apiUse.sourceLine = use.getSourceLine();
            apiUse.targetLine = use.getTargetLine();
        }
        return apiUse;
    }

    private API.Feedback feedbackToAPIRequest(im.tny.segvault.disturbances.model.Feedback f) {
        API.Feedback request = new API.Feedback();
        request.id = f.getId();
        request.timestamp = new long[]{f.getTimestamp().getTime() / 1000, (f.getTimestamp().getTime() % 1000) * 1000000};
        request.type = f.getType();
        request.contents = f.getContents();
        return request;
    }

    private class SyncTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            sync();
            return null;
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case MainService.ACTION_TRIP_REALM_UPDATED:
                case MainService.ACTION_FEEDBACK_REALM_UPDATED:
                    // TODO. this may cause cycles (we change the realm ourselves)
                    new SyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    break;
            }
        }
    };
}
