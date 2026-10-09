package br.com.redeserido.tvindoor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Abre o TV Indoor sozinho quando a TV liga. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent a = new Intent(c, MainActivity.class);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(a); } catch (Exception e) { }
    }
}
