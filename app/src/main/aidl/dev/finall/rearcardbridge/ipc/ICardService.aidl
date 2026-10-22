package dev.finall.rearcardbridge.ipc;
import android.os.ParcelFileDescriptor;
interface ICardService {
    String stageInstallPackage(String taskId, in ParcelFileDescriptor packageFd) = 4;
    String clearInstallStage(String taskId) = 5;
    String restoreCarrier(String cardId, String backupPath) = 6;
    void destroy() = 16777114;
}
