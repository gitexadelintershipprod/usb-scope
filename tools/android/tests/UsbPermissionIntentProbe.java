import android.content.Intent;
import android.content.IntentFilter;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Method;

/**
 * Run with app_process on an Android emulator, loading the actual helper from the built APK.
 * This uses the device's real IntentFilter implementation, rather than desktop android.jar stubs.
 * It performs no USB transfers or authorization changes and is never packaged in the app.
 */
public final class UsbPermissionIntentProbe {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("APK path required");
        ClassLoader loader = new DexClassLoader(args[0], null, null,
                ClassLoader.getSystemClassLoader());
        Class<?> helper = Class.forName(
                "com.technicallyvu.scope.usb.UsbPermissionIntents", true, loader);
        Method createIntent = helper.getMethod("createIntent", String.class, String.class);
        Method createFilter = helper.getMethod("createFilter", String.class);
        String pkg = "com.gitexadelintershipprod.usbscope";
        String token = "931c7167-a538-4e73-a8ef-c0b71915b7da";
        Intent callback = (Intent) createIntent.invoke(null, pkg, token);
        IntentFilter production = (IntentFilter) createFilter.invoke(null, pkg);

        check("package scoped", pkg.equals(callback.getPackage()));
        check("token retained", token.equals(callback.getData().getLastPathSegment()));
        check("old action-only filter rejects callback URI",
                match(new IntentFilter(callback.getAction()), callback) == IntentFilter.NO_MATCH_DATA);
        check("production filter accepts actual callback", match(production, callback) >= 0);
        check("other tokens route for subsequent request validation", match(production,
                (Intent) createIntent.invoke(null, pkg, "another-request-token")) >= 0);
        check("null data rejected", match(production, new Intent(callback).setData(null)) < 0);
        check("wrong scheme rejected", match(production, new Intent(callback).setData(
                callback.getData().buildUpon().scheme("wrong-scheme").build())) < 0);
        check("wrong authority rejected", match(production, new Intent(callback).setData(
                callback.getData().buildUpon().authority("another.application").build())) < 0);
        check("wrong action rejected", match(production,
                new Intent(callback).setAction("another.action")) < 0);
        check("MIME type rejected", match(production, new Intent(callback).setDataAndType(
                callback.getData(), "application/octet-stream")) < 0);
        check("filter for another package rejects this callback", match(
                (IntentFilter) createFilter.invoke(null, "another.application"), callback) < 0);
        System.out.println("PASS: " + checks + " actual Android IntentFilter checks");
        System.exit(0);
    }

    private static int match(IntentFilter filter, Intent intent) {
        return filter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                intent.getData(), intent.getCategories(), "UsbPermissionIntentProbe");
    }

    private static void check(String label, boolean success) {
        if (!success) throw new AssertionError(label);
        checks++;
        System.out.println("PASS: " + label);
    }
}
