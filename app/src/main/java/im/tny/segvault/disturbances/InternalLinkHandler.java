package im.tny.segvault.disturbances;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import javax.annotation.Nullable;

import im.tny.segvault.disturbances.ui.activity.LineActivity;
import im.tny.segvault.disturbances.ui.activity.POIActivity;
import im.tny.segvault.disturbances.ui.activity.StationActivity;
import im.tny.segvault.disturbances.ui.util.RichTextUtils;
import im.tny.segvault.subway.Line;
import im.tny.segvault.subway.Network;
import im.tny.segvault.subway.Station;

public class InternalLinkHandler implements RichTextUtils.ClickSpan.OnClickListener, Util.OnLineStatusSpanClickListener {
    public interface FallbackLinkHandler {
        void onClick(String url);
    }

    private FallbackLinkHandler fallbackHandler;
    private Context context;

    public InternalLinkHandler(Context context) {
        this.context = context;
    }

    public InternalLinkHandler(Context context, FallbackLinkHandler handler) {
        this.context = context;
        fallbackHandler = handler;
    }

    @Override
    public void onClick(String url) {
        onClick(context, url, fallbackHandler);
    }

    public static void onClick(Context context, String url, @Nullable FallbackLinkHandler fallbackHandler) {
        String[] parts = url.split(":");
        switch (parts[0]) {
            case "station":
                if (parts.length > 3 && parts[2].equals("lobby")) {
                    if (parts.length > 5 && parts[4].equals("exit")) {
                        onStationLinkClicked(context, parts[1], parts[3], parts[5]);
                    } else {
                        onStationLinkClicked(context, parts[1], parts[3]);
                    }
                } else {
                    onStationLinkClicked(context, parts[1]);
                }
                break;
            case "line":
                onLineLinkClicked(context, parts[1]);
                break;
            case "poi":
                onPOILinkClicked(context, parts[1]);
                break;
            case "mailto":
                onMailtoLinkClicked(context, url.substring(7));
                break;
            default:
                if (fallbackHandler != null) {
                    fallbackHandler.onClick(url);
                } else {
                    onLinkClicked(context, url);
                }
                break;
        }
    }

    public static void onStationLinkClicked(Context context, String destination) {
        onStationLinkClicked(context, destination, null);
    }

    public static void onStationLinkClicked(Context context, String destination, String lobby) {
        onStationLinkClicked(context, destination, lobby, null);
    }

    public static void onStationLinkClicked(Context context, String destination, String lobby, String exit) {
        for (Network network : Coordinator.get(context).getMapManager().getNetworks()) {
            Station station;
            if ((station = network.getStation(destination)) != null) {
                Intent intent = new Intent(context, StationActivity.class);
                intent.putExtra(StationActivity.EXTRA_STATION_ID, station.getId());
                intent.putExtra(StationActivity.EXTRA_NETWORK_ID, network.getId());
                if (lobby != null && !lobby.isEmpty()) {
                    intent.putExtra(StationActivity.EXTRA_LOBBY_ID, lobby);
                }
                if (exit != null && !exit.isEmpty()) {
                    intent.putExtra(StationActivity.EXTRA_EXIT_ID, Integer.parseInt(exit));
                }
                context.startActivity(intent);
                return;
            }
        }
    }

    public static void onLineLinkClicked(Context context, String destination) {
        for (Network network : Coordinator.get(context).getMapManager().getNetworks()) {
            Line line;
            if ((line = network.getLine(destination)) != null) {
                Intent intent = new Intent(context, LineActivity.class);
                intent.putExtra(LineActivity.EXTRA_LINE_ID, line.getId());
                intent.putExtra(LineActivity.EXTRA_NETWORK_ID, network.getId());
                context.startActivity(intent);
                return;
            }
        }
    }

    public static void onPOILinkClicked(Context context, String destination) {
        Intent intent = new Intent(context, POIActivity.class);
        intent.putExtra(POIActivity.EXTRA_POI_ID, destination);
        context.startActivity(intent);
    }

    public static void onMailtoLinkClicked(Context context, String address) {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:")); // only email apps should handle this
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{address});
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        }
    }

    public static void onLinkClicked(Context context, String destination) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(destination));
        if (browserIntent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(browserIntent);
        }
    }
}
