package dev.finall.rearcardbridge.ipc;
import android.os.ParcelFileDescriptor;
interface ICardService {
    String listCards() = 0;
    ParcelFileDescriptor openPreviewImage(String cardId) = 1;
    String replaceCard(String cardId, in ParcelFileDescriptor packageFd) = 2;
    String restoreCard(String cardId) = 3;
    void destroy() = 16777114;
}
