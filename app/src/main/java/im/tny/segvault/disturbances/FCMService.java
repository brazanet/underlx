package im.tny.segvault.disturbances;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Created by gabriel on 4/15/17.
 */

public class FCMService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        switch(remoteMessage.getFrom()) {
            case "/topics/disturbances":
                handleDisturbanceMessage(remoteMessage);
                break;
        }
    }

    private void handleDisturbanceMessage(RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        if(!data.containsKey("network") || !data.containsKey("line") || !data.containsKey("disturbance")
                || !data.containsKey("status") || !data.containsKey("downtime")) {
            return;
        }

        LocationService.showDisturbanceNotification(getApplicationContext(),
                data.get("network"), data.get("line"), data.get("disturbance"), data.get("status"),
                data.get("downtime").equals("true"));
    }


}
