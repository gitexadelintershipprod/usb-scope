package com.technicallyvu.scope.usb;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free device test. No USB device or real permission change is involved. */
public final class UsbPermissionIntentInstrumentation extends Instrumentation {
    private int checks;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            assertDelivery(true, 7311);
            assertDelivery(false, 7312);
            result.putString("status", "passed");
            result.putInt("checks", checks);
            result.putString("stream", "PASS: " + checks + " same-UID mutable one-shot callback checks\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("status", "failed");
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void assertDelivery(boolean granted, int requestCode) throws Exception {
        Context context = getTargetContext();
        String token = "device-test-request-" + requestCode;
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Intent> received = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                received.set(intent);
                delivered.countDown();
            }
        };
        // The API 37 run verifies the same non-exported policy as the production receiver.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, UsbPermissionIntents.createFilter(context.getPackageName()),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, UsbPermissionIntents.createFilter(context.getPackageName()));
        }
        PendingIntent pending = null;
        try {
            pending = PendingIntent.getBroadcast(context, requestCode,
                    UsbPermissionIntents.createIntent(context.getPackageName(), token),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT);
            check("target process UID", context.getApplicationInfo().uid == Process.myUid());
            check("PendingIntent creator UID", pending.getCreatorUid() == Process.myUid());
            pending.send(context, 0, new Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted));
            check("callback delivered", delivered.await(5, TimeUnit.SECONDS));
            Intent reply = received.get();
            check("callback token", token.equals(reply.getData().getLastPathSegment()));
            check("callback extra exists", reply.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED));
            check("mutable callback extra retained",
                    reply.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, !granted) == granted);
            boolean canceled = false;
            try {
                pending.send();
            } catch (PendingIntent.CanceledException expected) {
                canceled = true;
            }
            check("one-shot cannot be replayed", canceled);
        } finally {
            if (pending != null) pending.cancel();
            context.unregisterReceiver(receiver);
        }
    }

    private void check(String label, boolean success) {
        if (!success) throw new AssertionError(label);
        checks++;
    }
}
