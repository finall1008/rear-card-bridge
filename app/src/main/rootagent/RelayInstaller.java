import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Root app_process entry for rear-card install/update/remove/count.
 * Refuses to run unless RELAY_TOKEN matches the final argument.
 */
public final class RelayInstaller {
    static final String DESCRIPTOR = "com.xiaomi.subscreencenter.service.ISubScreen";
    private static final int TX_INSTALL = 11;
    private static final int TX_UPDATE = 12;
    private static final int TX_REMOVE = 13;
    private static final int TX_COUNT = 14;
    private static final File CARRIER_ROOT = new File(
        "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app");

    private RelayInstaller() {}

    public static void main(String[] args) {
        int status = 1;
        try {
            status = run(args);
        } catch (Throwable error) {
            String message = error.getMessage();
            System.err.println(error.getClass().getSimpleName()
                + (message == null || message.length() == 0 ? "" : ": " + message));
            status = 1;
        }
        System.exit(status);
    }

    private static int run(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("action and token are required");
        }
        String token = args[args.length - 1];
        if (!tokenMatches(token)) {
            System.err.println("RELAY_TOKEN mismatch");
            return 2;
        }
        String action = args[0];
        if ("count".equals(action)) {
            if (args.length != 2) throw new IllegalArgumentException("count <token>");
            int count = queryCount(subScreen());
            System.out.println("RESULT=" + count);
            return 0;
        }
        if ("remove".equals(action)) {
            if (args.length != 3) throw new IllegalArgumentException("remove <cardId> <token>");
            String cardId = requireCardId(args[1]);
            IBinder service = subScreen();
            Parcel request = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                request.writeString(cardId);
                int result = transact(service, TX_REMOVE, request);
                System.out.println("RESULT=" + result);
                return result == 0 ? 0 : 1;
            } finally {
                request.recycle();
            }
        }
        if ("restore".equals(action)) {
            if (args.length != 4) throw new IllegalArgumentException("restore <cardId> <backupPath> <token>");
            String cardId = requireCardId(args[1]);
            File backup = new File(args[2]);
            if (!backup.isFile()) throw new IllegalStateException("备份文件不存在");
            File target = new File(CARRIER_ROOT, cardId + "_extracted/rearscreen");
            if (!target.isFile()) throw new IllegalStateException("载体资源不存在");
            FileOutputStream out = new FileOutputStream(target);
            FileInputStream in = new FileInputStream(backup);
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.getFD().sync();
            } finally {
                in.close();
                out.close();
            }
            System.out.println("RESULT=0");
            return 0;
        }
        if (!"install".equals(action) && !"update".equals(action)) {
            throw new IllegalArgumentException("Unknown action");
        }
        if (args.length != 10) {
            throw new IllegalArgumentException(
                action + " <mrc> <icon> <preview> <cardId> <cardName> <targetDir> <resourceRevision> <expectedCount> <token>");
        }
        File mrc = requireFile(args[1]);
        File icon = requireFile(args[2]);
        File preview = requireFile(args[3]);
        String cardId = requireCardId(args[4]);
        String cardName = args[5] == null ? "" : args[5].trim();
        if (cardName.length() == 0) throw new IllegalArgumentException("empty cardName");
        File targetDir = new File(args[6]);
        if (!targetDir.isAbsolute()) throw new IllegalArgumentException("targetDir must be absolute");
        long revision = Long.parseLong(args[7]);
        if (revision < 0) throw new IllegalArgumentException("negative resourceRevision");
        int expectedCount = Integer.parseInt(args[8]);
        File revDir = new File(targetDir, Long.toString(revision));
        publish(mrc, icon, preview, revDir);
        String revPath = revDir.getAbsolutePath();
        IBinder service = subScreen();
        Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            writeWidget(request, cardId, cardName,
                new File(revPath, "app_icon.png").getAbsolutePath(),
                new File(revPath, "preview.png").getAbsolutePath(),
                new File(revPath, "rearScreen.mrc").getAbsolutePath());
            int code = "install".equals(action) ? TX_INSTALL : TX_UPDATE;
            int result = transact(service, code, request);
            System.out.println("RESULT=" + result);
            if (result != 0) return 1;
        } finally {
            request.recycle();
        }
        // The insert result arrives before the service applies it to its in-memory list;
        // poll tx14 briefly instead of trusting a single snapshot.
        int count = -1;
        for (int attempt = 0; attempt < 6; attempt++) {
            count = queryCount(service);
            if (count >= expectedCount) break;
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("COUNT=" + count);
        if (count < expectedCount) {
            System.err.println("count " + count + " is below expected " + expectedCount);
            return 4;
        }
        return 0;
    }

    /**
     * Widget parcel matching the ROM that accepted insert (transaction 11).
     * changed/nfc are int 0. configPath, component and options are each a -1 null header;
     * writing 0 for the component makes the server read a string and return -4.
     */
    private static void writeWidget(Parcel request, String cardId, String cardName,
                                    String iconPath, String previewPath, String mrcPath) {
        Bundle extra = new Bundle();
        extra.putString("resId", cardId);
        extra.putString("appName", cardName);
        extra.putInt("appCardType", 2);
        extra.putString("appIconPath", iconPath);
        extra.putString("previewLightPath", previewPath);
        extra.putString("previewDarkPath", previewPath);
        extra.putBoolean("isGame", false);
        extra.putBoolean("isPreset", false);
        request.writeInt(1);
        request.writeInt(0);
        request.writeInt(7);
        request.writeBoolean(false);
        request.writeBundle(extra);
        request.writeBoolean(false);
        request.writeString(mrcPath);
        request.writeInt(-1);
        request.writeInt(-1);
        request.writeInt(-1);
    }

    private static void publish(File mrc, File icon, File preview, File revDir) throws Exception {
        if (!revDir.isDirectory() && !revDir.mkdirs()) {
            throw new IllegalStateException("mkdir failed: " + revDir.getAbsolutePath());
        }
        copy(mrc, new File(revDir, "rearScreen.mrc"));
        copy(icon, new File(revDir, "app_icon.png"));
        copy(preview, new File(revDir, "preview.png"));
        String dir = revDir.getAbsolutePath();
        requireCommand(new String[]{"chmod", "0755", dir}, "chmod");
        requireCommand(new String[]{"chmod", "0644",
            new File(dir, "rearScreen.mrc").getAbsolutePath(),
            new File(dir, "app_icon.png").getAbsolutePath(),
            new File(dir, "preview.png").getAbsolutePath()}, "chmod");
        requireCommand(new String[]{"chown", "-R", "system_theme:system_theme", dir}, "chown");
        try {
            runCommand(new String[]{"restorecon", "-R", dir});
        } catch (IOException ignored) {
            // restorecon is optional; a missing toybox binary is not fatal.
        }
    }

    private static IBinder subScreen() throws Exception {
        Class<?> amClass = Class.forName("android.app.ActivityManager");
        Object am = amClass.getMethod("getService").invoke(null);
        Method peek = Class.forName("android.app.IActivityManager")
            .getMethod("peekService", Intent.class, String.class, String.class);
        Intent target = new Intent().setComponent(new ComponentName(
            "com.xiaomi.subscreencenter",
            "com.xiaomi.subscreencenter.service.SubScreenService"));
        IBinder service = (IBinder) peek.invoke(am, target, "", "com.android.shell");
        if (service == null || !DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
            throw new IllegalStateException("Expected running SubScreenService");
        }
        return service;
    }

    private static int queryCount(IBinder service) {
        Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            return transact(service, TX_COUNT, request);
        } finally {
            request.recycle();
        }
    }

    private static int transact(IBinder service, int code, Parcel request) {
        Parcel response = Parcel.obtain();
        try {
            boolean delivered;
            try {
                delivered = service.transact(code, request, response, 0);
            } catch (RemoteException error) {
                throw new IllegalStateException("binder transact failed: " + code, error);
            }
            if (!delivered) {
                throw new IllegalStateException("binder transact failed: " + code);
            }
            response.readException();
            return response.readInt();
        } finally {
            response.recycle();
        }
    }

    private static boolean tokenMatches(String provided) {
        String expected = System.getenv("RELAY_TOKEN");
        if (expected == null || provided == null || provided.length() == 0) return false;
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireCardId(String cardId) {
        if (cardId == null || cardId.length() == 0 || cardId.indexOf('/') >= 0 || cardId.indexOf('\\') >= 0
            || ".".equals(cardId) || "..".equals(cardId)) {
            throw new IllegalArgumentException("invalid cardId");
        }
        return cardId;
    }

    private static File requireFile(String path) {
        File file = new File(path);
        if (!file.isFile()) throw new IllegalArgumentException("missing file: " + path);
        return file;
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            out.getFD().sync();
        }
    }

    private static void requireCommand(String[] argv, String name) throws Exception {
        int code = runCommand(argv);
        if (code != 0) throw new IllegalStateException(name + " failed: " + code);
    }

    private static int runCommand(String[] argv) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(argv);
        ByteCollector out = new ByteCollector(process.getInputStream());
        ByteCollector err = new ByteCollector(process.getErrorStream());
        out.start();
        err.start();
        int code = process.waitFor();
        out.join();
        err.join();
        if (code != 0) {
            String detail = err.text().trim();
            if (detail.length() > 0) System.err.println(detail);
        }
        return code;
    }

    private static final class ByteCollector extends Thread {
        private final InputStream source;
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ByteCollector(InputStream source) {
            this.source = source;
        }

        @Override public void run() {
            try {
                byte[] buf = new byte[1024];
                int n;
                while ((n = source.read(buf)) >= 0) sink.write(buf, 0, n);
            } catch (IOException ignored) {
            }
        }

        String text() {
            return new String(sink.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
