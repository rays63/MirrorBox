package tv.local.airreceiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver after boot and after an update, so the TV is always discoverable. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ReceiverService.start(context);
    }
}
